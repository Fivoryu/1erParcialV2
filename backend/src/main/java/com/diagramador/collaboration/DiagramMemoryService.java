package com.diagramador.collaboration;

import com.diagramador.assistant.AssistantJson;
import com.diagramador.assistant.ChatAssistantProvider;
import com.diagramador.api.ApiException;
import com.diagramador.api.DiagramMemoryResource;
import com.diagramador.domain.ChatSession;
import com.diagramador.domain.DiagramMessage;
import com.diagramador.domain.DiagramMemory;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.MemoryKind;
import com.diagramador.domain.MemoryStatus;
import com.diagramador.domain.MessageKind;
import com.diagramador.domain.ModelCommand;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.persistence.DiagramMemoryRepository;
import com.diagramador.persistence.DiagramMessageRepository;
import com.diagramador.persistence.ModelCommandRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Diagram-scoped memory (T5/L43). One extraction run takes the pending sources (chat messages of
 * the view's sessions plus accepted model commands), makes a SINGLE provider call to classify and
 * redact memories, validates every candidate (invalid payloads confirm nothing, CA-13), consumes
 * the material exactly once (CA-21), and keeps contradictions and explicit replacements explicit
 * (CA-16/CA-17). Extraction never runs per change; recovery and forgetting are separate from chat
 * history (CA-20/CA-22).
 */
@Service
public class DiagramMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(DiagramMemoryService.class);
    private static final int MAX_PENDING_MESSAGES = 20;
    private static final int MAX_PENDING_COMMANDS = 20;
    private static final int MAX_CONTENT_LENGTH = 2000;

    private final ChatService chatService;
    private final DiagramMemoryRepository memoryRepository;
    private final DiagramMessageRepository messageRepository;
    private final ModelCommandRepository commandRepository;
    private final CurrentUserContext currentUserContext;
    private final com.diagramador.identity.ProjectAuthorizationService projectAuthorizationService;
    private final com.diagramador.persistence.UserAccountRepository userAccountRepository;
    private final ChatAssistantProvider provider;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public record ExtractionResult(int memoriesCreated, int conflictsMarked, int replacements, boolean consumed) {
    }

    public DiagramMemoryService(ChatService chatService, DiagramMemoryRepository memoryRepository,
            DiagramMessageRepository messageRepository, ModelCommandRepository commandRepository,
            CurrentUserContext currentUserContext,
            com.diagramador.identity.ProjectAuthorizationService projectAuthorizationService,
            com.diagramador.persistence.UserAccountRepository userAccountRepository,
            ChatAssistantProvider provider, ObjectMapper objectMapper,
            @Value("${diagrammer.assistant.enabled:false}") boolean enabled) {
        this.chatService = chatService;
        this.memoryRepository = memoryRepository;
        this.messageRepository = messageRepository;
        this.commandRepository = commandRepository;
        this.currentUserContext = currentUserContext;
        this.projectAuthorizationService = projectAuthorizationService;
        this.userAccountRepository = userAccountRepository;
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Transactional
    public ExtractionResult extract(UUID projectId, UUID viewId) {
        if (!enabled) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_DISABLED",
                    "The AI assistant is not enabled on this deployment.");
        }
        DiagramView view = chatService.requireAccessibleView(projectId, viewId);
        List<DiagramMessage> pendingMessages = messageRepository
                .findBySession_View_IdAndKindAndMemoryExtractedFalseOrderByCreatedAtAscIdAsc(view.getId(),
                        MessageKind.HUMAN, PageRequest.of(0, MAX_PENDING_MESSAGES));
        UUID modelId = view.getModel().getId();
        List<ModelCommand> pendingCommands = commandRepository
                .findByModelIdAndKnowledgePendingTrueOrderByCreatedAtAscCommandIdAsc(modelId,
                        PageRequest.of(0, MAX_PENDING_COMMANDS));
        if (pendingMessages.isEmpty() && pendingCommands.isEmpty()) {
            // Nothing pending: no provider call at all (S12: never per change).
            return new ExtractionResult(0, 0, 0, false);
        }

        List<DiagramMemory> existing =
                memoryRepository.findByView_IdAndStatusOrderByCreatedAtAscIdAsc(view.getId(),
                        MemoryStatus.ACTIVE);
        String raw;
        try {
            raw = provider.chat(List.of(new ChatAssistantProvider.Turn("user",
                    buildPrompt(existing, pendingMessages, pendingCommands))));
        } catch (RuntimeException exception) {
            logger.warn("Memory extraction provider call failed for view {}", viewId, exception);
            // The material stays pending: a later run can retry (CA-13).
            return new ExtractionResult(0, 0, 0, false);
        }

        Map<String, Object> parsed = AssistantJson.extract(objectMapper, raw);
        if (parsed == null || !(parsed.get("memories") instanceof List<?> rawMemories)) {
            // Invalid provider payload confirms nothing and keeps the material retryable (CA-13).
            return new ExtractionResult(0, 0, 0, false);
        }

        Set<UUID> pendingMessageIds = new HashSet<>();
        pendingMessages.forEach(message -> pendingMessageIds.add(message.getId()));
        Map<UUID, DiagramMemory> created = new LinkedHashMap<>();
        int conflicts = 0;
        int replacements = 0;
        for (Object candidateObject : rawMemories) {
            if (!(candidateObject instanceof Map<?, ?> rawMap)) {
                continue;
            }
            DiagramMemory memory = buildCandidate(view, rawMap, pendingMessages, pendingMessageIds, pendingCommands);
            if (memory == null || created.containsKey(memory.getId())) {
                continue;
            }
            memoryRepository.saveAndFlush(memory);
            created.put(memory.getId(), memory);
            UUID replaces = parseUuid(candidateValue(rawMap, "replaces"));
            if (replaces != null) {
                DiagramMemory predecessor = activeMemoryOfView(replaces, view.getId());
                if (predecessor != null && !predecessor.getId().equals(memory.getId())) {
                    predecessor.setStatus(MemoryStatus.REPLACED);
                    predecessor.setReplacedBy(memory);
                    replacements++;
                }
            }
            for (UUID conflictingWith : parseUuidList(candidateValue(rawMap, "conflictingWith"))) {
                DiagramMemory other = activeMemoryOfView(conflictingWith, view.getId());
                if (other != null && !other.getId().equals(memory.getId())) {
                    other.setStatus(MemoryStatus.CONFLICTED);
                    memory.setStatus(MemoryStatus.CONFLICTED);
                    conflicts++;
                }
            }
        }

        // The delivered material is consumed exactly once per successful run (CA-21), even when
        // the provider proposed nothing: sources are never re-fed.
        if (!pendingMessages.isEmpty()) {
            messageRepository.markMemoryExtracted(pendingMessages.stream().map(DiagramMessage::getId).toList());
        }
        if (!pendingCommands.isEmpty()) {
            commandRepository.markKnowledgeConsumedForCommands(
                    pendingCommands.stream().map(ModelCommand::getCommandId).toList());
        }
        return new ExtractionResult(created.size(), conflicts, replacements, true);
    }

    @Transactional(readOnly = true)
    public List<DiagramMemoryResource> memories(UUID projectId, UUID viewId, String status) {
        DiagramView view = chatService.requireAccessibleView(projectId, viewId);
        List<DiagramMemory> memories = status == null
                ? memoryRepository.findByView_IdOrderByCreatedAtAscIdAsc(view.getId())
                : memoryRepository.findByView_IdAndStatusOrderByCreatedAtAscIdAsc(view.getId(),
                        parseStatus(status));
        return memories.stream().map(DiagramMemoryService::memoryResource).toList();
    }

    /**
     * PD-01 analog for memory (L43): only the OWNER forgets. Physical delete of one memory; its
     * already-consumed source is never re-fed, so nothing resurrects (CA-21).
     */
    @Transactional
    public Map<String, Integer> forgetOne(UUID projectId, UUID viewId, UUID memoryId) {
        DiagramView view = chatService.requireAccessibleView(projectId, viewId);
        projectAuthorizationService.requireOwner(projectId);
        DiagramMemory memory = memoryRepository.findById(memoryId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "The memory was not found for this diagram."));
        if (!memory.getView().getId().equals(view.getId())) {
            // A memory of another diagram is indistinguishable from a missing one (CA-06).
            throw error(HttpStatus.NOT_FOUND, "NOT_FOUND", "The memory was not found for this diagram.");
        }
        memoryRepository.delete(memory);
        return Map.of("deletedMemories", 1);
    }

    /** CA-22: forget the whole diagram's memory at once; the UML and other diagrams are untouched. */
    @Transactional
    public Map<String, Integer> forgetAll(UUID projectId, UUID viewId) {
        DiagramView view = chatService.requireAccessibleView(projectId, viewId);
        projectAuthorizationService.requireOwner(projectId);
        return Map.of("deletedMemories", memoryRepository.deleteByView_Id(view.getId()));
    }

    private DiagramMemory activeMemoryOfView(UUID memoryId, UUID viewId) {
        return memoryRepository.findById(memoryId)
                .filter(memory -> memory.getView().getId().equals(viewId))
                .orElse(null);
    }

    private DiagramMemory buildCandidate(DiagramView view, Map<?, ?> rawMap, List<DiagramMessage> pendingMessages,
            Set<UUID> pendingMessageIds, List<ModelCommand> pendingCommands) {
        Map<String, Object> candidate = stringKeys(rawMap);
        String content = stringValue(candidate.get("content"));
        MemoryKind kind = parseKind(candidate.get("kind"));
        UUID sourceMessageId = parseUuid(candidate.get("sourceMessageId"));
        if (content == null || content.isBlank() || content.length() > MAX_CONTENT_LENGTH
                || kind == null || sourceMessageId == null || !pendingMessageIds.contains(sourceMessageId)) {
            // An invalid candidate is discarded, never confirmed (CA-13).
            return null;
        }
        DiagramMessage source = pendingMessages.stream()
                .filter(message -> message.getId().equals(sourceMessageId))
                .findFirst()
                .orElse(null);
        UUID parsedCommandId = parseUuid(candidate.get("sourceCommandId"));
        boolean commandCitable = parsedCommandId != null && pendingCommands.stream()
                .anyMatch(command -> command.getCommandId().equals(parsedCommandId));
        UUID sourceCommandId = commandCitable ? parsedCommandId : null;
        DiagramMemory memory = DiagramMemory.create(view, kind, content.strip(), source == null ? null : source.getSession(),
                source, currentUser());
        memory.setSourceCommandId(sourceCommandId);
        return memory;
    }

    private UserAccount currentUser() {
        UUID userId = currentUserContext.require().userId();
        return userAccountRepository.findById(userId).orElse(null);
    }

    private String buildPrompt(List<DiagramMemory> existing, List<DiagramMessage> pendingMessages,
            List<ModelCommand> pendingCommands) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                Administra la memoria de un diagrama UML. Recibís (1) recuerdos existentes con su memoryId, \
                (2) mensajes pendientes y (3) cambios aceptados pendientes. Clasificá cada observación como \
                DECISION (decisión expresa del equipo) o HYPOTHESIS (interpretación tuya o ambigua, nunca \
                confirmada). Cada recuerdo debe citar sourceMessageId con el messageId del mensaje que la \
                origina. Si una decisión reemplaza expresamente un recuerdo existente, poné "replaces" con ese \
                memoryId; si contradice un recuerdo de otra sesión, poné "conflictingWith" con esos memoryId. \
                No inventes ids. Respondé ÚNICAMENTE JSON:\n\
                {"memories":[{"content":"...","kind":"DECISION|HYPOTHESIS","sourceMessageId":"...","replaces":null,"conflictingWith":[]}]}\n\n\
                """);
        prompt.append("MEMORIAS EXISTENTES: ").append(json(Map.of(
                "memories", existing.stream().map(memory -> Map.of(
                        "memoryId", String.valueOf(memory.getId()),
                        "kind", memory.getKind().name(),
                        "content", memory.getContent())).toList()))).append('\n');
        prompt.append("MENSAJES PENDIENTES: ").append(json(Map.of(
                "messages", pendingMessages.stream().map(message -> Map.of(
                        "messageId", String.valueOf(message.getId()),
                        "sessionTitle", message.getSession().getTitle(),
                        "body", message.getBody())).toList()))).append('\n');
        prompt.append("CAMBIOS PENDIENTES: ").append(json(Map.of(
                "changes", pendingCommands.stream().map(command -> Map.of(
                        "operation", command.getOperation(),
                        "origin", command.getOrigin() == null ? "" : command.getOrigin().name())).toList())));
        return prompt.toString();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "[]";
        }
    }

    private static MemoryStatus parseStatus(String status) {
        try {
            return MemoryStatus.valueOf(status.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD",
                    "status must be one of ACTIVE, CONFLICTED, REPLACED.");
        }
    }

    private static MemoryKind parseKind(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return MemoryKind.valueOf(text.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static UUID parseUuid(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static List<UUID> parseUuidList(Object value) {
        List<UUID> ids = new ArrayList<>();
        if (value instanceof List<?> rawList) {
            rawList.forEach(item -> {
                UUID id = parseUuid(item);
                if (id != null) {
                    ids.add(id);
                }
            });
        }
        return ids;
    }

    private static Map<String, Object> stringKeys(Map<?, ?> raw) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key instanceof String stringKey) {
                candidate.put(stringKey, value);
            }
        });
        return candidate;
    }

    private static Object candidateValue(Map<?, ?> candidate, String key) {
        return candidate.get(key);
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text.strip() : null;
    }

    public static DiagramMemoryResource memoryResource(DiagramMemory memory) {
        return new DiagramMemoryResource(memory.getId(), memory.getKind().name(), memory.getStatus().name(),
                memory.getContent(),
                memory.getSourceSession() == null ? null : memory.getSourceSession().getId(),
                memory.getSourceMessage() == null ? null : memory.getSourceMessage().getId(),
                memory.getReplacedBy() == null ? null : memory.getReplacedBy().getId(),
                memory.getCreatedAt());
    }

    private static ApiException error(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message, List.of(), null);
    }
}
