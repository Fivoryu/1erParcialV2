package com.diagramador.context;

import com.diagramador.assistant.AssistantJson;
import com.diagramador.assistant.ChatAssistantProvider;
import com.diagramador.assistant.DiagramContextRenderer;
import com.diagramador.api.ApiException;
import com.diagramador.collaboration.ChatService;
import com.diagramador.domain.ChatSession;
import com.diagramador.domain.ChatSessionCompaction;
import com.diagramador.domain.DiagramMessage;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.MemoryStatus;
import com.diagramador.domain.ModelCommand;
import com.diagramador.persistence.ChatSessionCompactionRepository;
import com.diagramador.persistence.DiagramMemoryRepository;
import com.diagramador.persistence.DiagramMessageRepository;
import com.diagramador.persistence.ModelCommandRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The context budget (T6/L45, PD-03): C=window, R reserved for the response BEFORE the prompt
 * budget P is computed, and compaction triggered exactly at ceil(0.8*P) (CA-24). The meter counts
 * instructions, diagram data, diagram memories and THIS session's live history (CA-27). A failed
 * summary or a still-oversized context refuses the ask with CONTEXT_OVERFLOW instead of silently
 * truncating (CA-26); compaction never deletes sources or confirms knowledge (CA-25).
 */
@Service
public class ContextBudgetService {

    private static final Logger logger = LoggerFactory.getLogger(ContextBudgetService.class);
    private static final int MAX_PENDING_MESSAGES = 20;
    private static final int MAX_PENDING_COMMANDS = 20;
    private static final int MAX_HISTORY_MESSAGES = 200;

    private final ChatService chatService;
    private final com.diagramador.api.ProjectApiService projectApiService;
    private final DiagramContextRenderer contextRenderer;
    private final DiagramMemoryRepository memoryRepository;
    private final DiagramMessageRepository messageRepository;
    private final ModelCommandRepository commandRepository;
    private final ChatSessionCompactionRepository compactionRepository;
    private final ChatAssistantProvider provider;
    private final TokenCounter tokenCounter;
    private final ObjectMapper objectMapper;
    private final long contextWindow;
    private final long reservedForResponse;
    private final long promptBudget;

    public record ContextReport(long contextWindow, long reservedForResponse, long promptBudget, long instructions,
            long diagramData, long memories, long history, long totalUsed, long promptThreshold,
            boolean compactionPending, int percent) {
    }

    public record CompactionOutcome(boolean compacted, String reason) {
    }

    /** Everything the ask flow needs: the counted report, the context turn and the live turns. */
    public record LiveContext(ContextReport report, String contextTurn, List<ChatAssistantProvider.Turn> liveTurns,
            ChatAssistantProvider.Turn questionTurn) {
    }

    public ContextBudgetService(ChatService chatService,
            com.diagramador.api.ProjectApiService projectApiService,
            DiagramContextRenderer contextRenderer, DiagramMemoryRepository memoryRepository,
            DiagramMessageRepository messageRepository, ModelCommandRepository commandRepository,
            ChatSessionCompactionRepository compactionRepository, ChatAssistantProvider provider,
            TokenCounter tokenCounter, ObjectMapper objectMapper,
            @Value("${diagrammer.context.window:4096}") long contextWindow,
            @Value("${diagrammer.context.response-reserved:512}") long reservedForResponse,
            @Value("${diagrammer.context.prompt-budget:3584}") long promptBudget) {
        // CA-23: a broken budget configuration is rejected at startup, never at ask time.
        if (contextWindow <= 0 || promptBudget <= 0 || reservedForResponse < 0
                || promptBudget > contextWindow - reservedForResponse) {
            throw new IllegalStateException("Invalid context budget configuration (CA-23): window=" + contextWindow
                    + ", reserved=" + reservedForResponse + ", promptBudget=" + promptBudget);
        }
        this.chatService = chatService;
        this.projectApiService = projectApiService;
        this.contextRenderer = contextRenderer;
        this.memoryRepository = memoryRepository;
        this.messageRepository = messageRepository;
        this.commandRepository = commandRepository;
        this.compactionRepository = compactionRepository;
        this.provider = provider;
        this.tokenCounter = tokenCounter;
        this.objectMapper = objectMapper;
        this.contextWindow = contextWindow;
        this.reservedForResponse = reservedForResponse;
        this.promptBudget = promptBudget;
    }

    @Transactional(readOnly = true)
    public ContextReport meter(UUID projectId, UUID sessionId) {
        ChatSession session = chatService.requireAccessibleSession(projectId, sessionId);
        return report(session, projectId);
    }

    /**
     * Explicit compaction (CA-24/25): one provider call summarizes the live segment preserving the
     * decision/hypothesis/pending distinction; sources are never deleted. A failed summary is
     * reported honestly and leaves everything untouched (CA-26).
     */
    @Transactional
    public CompactionOutcome compact(UUID projectId, UUID sessionId) {
        ChatSession session = chatService.requireAccessibleSession(projectId, sessionId);
        List<DiagramMessage> live = liveMessages(session);
        if (live.isEmpty()) {
            return new CompactionOutcome(false, "Nothing to compact.");
        }
        String raw;
        try {
            raw = provider.chat(List.of(new ChatAssistantProvider.Turn("user", buildCompactionPrompt(live))));
        } catch (RuntimeException exception) {
            logger.warn("Compaction provider call failed for session {}", sessionId, exception);
            return new CompactionOutcome(false, "The provider call failed.");
        }
        Map<String, Object> parsed = AssistantJson.extract(objectMapper, raw);
        if (parsed == null || !(parsed.get("summary") instanceof String summary) || summary.isBlank()) {
            return new CompactionOutcome(false, "The provider payload was invalid.");
        }
        ChatSessionCompaction compaction = compactionRepository.findBySession_Id(session.getId())
                .orElseGet(() -> ChatSessionCompaction.create(session, summaryBlock(parsed), lastId(live)));
        // Upsert: same id (MapsId) merges into the existing row, refreshing summary and cursor.
        compaction.setSummary(summaryBlock(parsed));
        compaction.setUpToMessageId(lastId(live));
        compactionRepository.save(compaction);
        return new CompactionOutcome(true, "ok");
    }

    /**
     * Called by the ask flow (CA-24): compacts when the meter crosses the threshold and refuses
     * with CONTEXT_OVERFLOW when the context still does not fit (CA-26).
     */
    @Transactional
    public LiveContext prepare(UUID projectId, UUID sessionId, UUID questionMessageId) {
        ChatSession session = chatService.requireAccessibleSession(projectId, sessionId);
        ContextReport before = report(session, projectId);
        if (before.compactionPending()) {
            compact(projectId, sessionId);
        }
        ContextReport after = report(session, projectId);
        if (after.totalUsed() > after.promptBudget()) {
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "CONTEXT_OVERFLOW",
                    "The context does not fit even after compaction; narrow the request (CA-26).");
        }
        return buildLiveContext(session, projectId, after, questionMessageId);
    }

    private ContextReport report(ChatSession session, UUID projectId) {
        long instructions = tokenCounter.count(provider.systemPrompt());
        String diagramJson = contextRenderer.render(projectApiService.getModel(projectId));
        long diagramData = tokenCounter.count(diagramJson);
        String memoriesJson = memoriesJson(session.getView().getId());
        long memories = tokenCounter.count(memoriesJson);

        ChatSessionCompaction compaction = compactionRepository.findBySession_Id(session.getId()).orElse(null);
        List<DiagramMessage> all = messageRepository
                .findBySession_IdOrderByCreatedAtAscIdAsc(session.getId());
        List<DiagramMessage> live = liveAfter(all, compaction);
        long history = (compaction == null ? 0 : tokenCounter.count(compaction.getSummary()))
                + live.stream().mapToLong(message -> tokenCounter.count(message.getBody())).sum();
        long totalUsed = instructions + diagramData + memories + history;
        long threshold = (promptBudget * 4 + 4) / 5; // ceil(0.8*P), computed over P only (CA-24)
        return new ContextReport(contextWindow, reservedForResponse, promptBudget, instructions, diagramData,
                memories, history, totalUsed, threshold, totalUsed >= threshold,
                (int) (totalUsed * 100 / Math.max(1, promptBudget)));
    }

    private LiveContext buildLiveContext(ChatSession session, UUID projectId, ContextReport report,
            UUID questionMessageId) {
        ChatSessionCompaction compaction = compactionRepository.findBySession_Id(session.getId()).orElse(null);
        List<DiagramMessage> all = messageRepository.findBySession_IdOrderByCreatedAtAscIdAsc(session.getId());
        List<DiagramMessage> live = liveAfter(all, compaction);
        StringBuilder contextTurn = new StringBuilder();
        contextTurn.append("Contexto actual del diagrama (JSON):\n")
                .append(contextRenderer.render(projectApiService.getModel(projectId)));
        contextTurn.append("\n\nMemorias del diagrama:\n").append(memoriesJson(session.getView().getId()));
        if (compaction != null) {
            contextTurn.append("\n\nRESUMEN de mensajes anteriores de esta conversación:\n")
                    .append(compaction.getSummary());
        }
        List<ChatAssistantProvider.Turn> liveTurns = new ArrayList<>();
        ChatAssistantProvider.Turn questionTurn = null;
        for (DiagramMessage message : live) {
            if (message.getId().equals(questionMessageId)) {
                // The asked question is always the provider's final turn (T3.6 contract), so it
                // travels separately from the live history.
                questionTurn = new ChatAssistantProvider.Turn("user", message.getBody());
                continue;
            }
            liveTurns.add(new ChatAssistantProvider.Turn(
                    "AI".equalsIgnoreCase(message.getKind().name()) ? "assistant" : "user",
                    message.getBody()));
        }
        return new LiveContext(report, contextTurn.toString(), List.copyOf(liveTurns), questionTurn);
    }

    private List<DiagramMessage> liveMessages(ChatSession session) {
        return liveAfter(messageRepository.findBySession_IdOrderByCreatedAtAscIdAsc(session.getId()),
                compactionRepository.findBySession_Id(session.getId()).orElse(null));
    }

    private static List<DiagramMessage> liveAfter(List<DiagramMessage> all, ChatSessionCompaction compaction) {
        if (compaction == null || compaction.getUpToMessageId() == null) {
            return all;
        }
        List<DiagramMessage> live = new ArrayList<>();
        boolean afterCursor = false;
        for (DiagramMessage message : all) {
            if (afterCursor) {
                live.add(message);
            } else if (message.getId().equals(compaction.getUpToMessageId())) {
                afterCursor = true;
            }
        }
        return live;
    }

    private String memoriesJson(UUID viewId) {
        try {
            var memories = memoryRepository.findByView_IdAndStatusOrderByCreatedAtAscIdAsc(viewId,
                    MemoryStatus.ACTIVE);
            var payload = memories.stream().map(memory -> Map.of(
                    "memoryId", String.valueOf(memory.getId()),
                    "kind", memory.getKind().name(),
                    "status", memory.getStatus().name(),
                    "content", memory.getContent())).toList();
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            logger.warn("Memories could not be serialized for the context", exception);
            return "[]";
        }
    }

    private String buildCompactionPrompt(List<DiagramMessage> live) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                Compactá el tramo de conversación siguiente en un resumen breve para un asistente de \
                diagramas UML. Preservá la distinción: decisiones expresas del equipo, hipótesis no \
                confirmadas y pendientes. Respondé ÚNICAMENTE JSON:\n\
                {"summary":"...","decisions":["..."],"hypotheses":["..."],"pending":["..."]}\n\n\
                TRAMO:\n\
                """);
        for (DiagramMessage message : live) {
            prompt.append(message.getKind().name()).append(": ").append(message.getBody()).append('\n');
        }
        return prompt.toString();
    }

    private String summaryBlock(Map<String, Object> parsed) {
        StringBuilder block = new StringBuilder(String.valueOf(parsed.get("summary")));
        appendSection(block, "Decisiones expresas", parsed.get("decisions"));
        appendSection(block, "Hipótesis pendientes", parsed.get("hypotheses"));
        appendSection(block, "Pendientes", parsed.get("pending"));
        return block.toString();
    }

    private void appendSection(StringBuilder block, String title, Object items) {
        if (items instanceof List<?> list && !list.isEmpty()) {
            block.append("\n").append(title).append(": ");
            for (Object item : list) {
                block.append(String.valueOf(item)).append("; ");
            }
        }
    }

    private static UUID lastId(List<DiagramMessage> messages) {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1).getId();
    }

    private static ApiException error(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message, List.of(), null);
    }
}
