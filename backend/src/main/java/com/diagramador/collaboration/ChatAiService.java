package com.diagramador.collaboration;

import com.diagramador.assistant.ChatAssistantProvider;
import com.diagramador.assistant.DiagramContextRenderer;
import com.diagramador.api.ApiException;
import com.diagramador.api.ChatResources.MessageResource;
import com.diagramador.api.ModelResource;
import com.diagramador.api.ProjectApiService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Single shared AI inference queue for the collaborative chat (T3, PD-04/L38): one running
 * inference plus four queued asks across the whole platform, explicit rejection beyond that, and
 * at most one pending request per session. The pending registry is intentionally in-memory;
 * after a process restart there is no pending request and a client may ask again (the answer
 * never reached persistence, so nothing is duplicated).
 */
@Service
public class ChatAiService {

    private static final Logger logger = LoggerFactory.getLogger(ChatAiService.class);
    private static final int MAX_QUEUE = 4;
    private static final int CONTEXT_HISTORY_MESSAGES = 10;

    private final ChatService chatService;
    private final ChatAssistantProvider provider;
    private final ProjectApiService projectApiService;
    private final DiagramContextRenderer contextRenderer;
    private final ProjectEventBus eventBus;
    private final boolean enabled;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(MAX_QUEUE), runnable -> {
                Thread thread = new Thread(runnable, "chat-ai-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentMap<UUID, PendingRequest> pending = new ConcurrentHashMap<>();

    private static final class PendingRequest {
        private final UUID questionMessageId;
        private volatile Future<?> future;
        private volatile String state = "QUEUED";
        /**
         * Who wins the QUEUED→RUNNING transition. FutureTask states stay NEW while a task is
         * executing, so Future.cancel cannot tell "queued" from "running": the gate is the
         * authority. First side to CAS it owns the outcome (L38).
         */
        private final AtomicBoolean startGate = new AtomicBoolean(false);

        private PendingRequest(UUID questionMessageId) {
            this.questionMessageId = questionMessageId;
        }
    }

    public record PendingResource(UUID sessionId, String state, UUID questionMessageId) {
    }

    public record AskAccepted(String state) {
    }

    public ChatAiService(ChatService chatService, ChatAssistantProvider provider,
            ProjectApiService projectApiService, DiagramContextRenderer contextRenderer,
            ProjectEventBus eventBus,
            @Value("${diagrammer.assistant.enabled:false}") boolean enabled) {
        this.chatService = chatService;
        this.provider = provider;
        this.projectApiService = projectApiService;
        this.contextRenderer = contextRenderer;
        this.eventBus = eventBus;
        this.enabled = enabled;
    }

    /**
     * Enqueues one ask. Authorization and target validation run here (request thread); the
     * provider call runs on the single worker thread. The context snapshot is captured now so
     * the answer reflects the diagram the question was asked against (CA-08).
     */
    public AskAccepted ask(UUID projectId, UUID sessionId, UUID messageId) {
        if (!enabled) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_DISABLED",
                    "The AI assistant is not enabled on this deployment.");
        }
        chatService.requireAskTarget(projectId, sessionId, messageId);
        PendingRequest request = new PendingRequest(messageId);
        if (pending.putIfAbsent(sessionId, request) != null) {
            throw error(HttpStatus.CONFLICT, "AI_REQUEST_IN_FLIGHT",
                    "This session already has a pending AI request.");
        }
        try {
            List<ChatAssistantProvider.Turn> turns = buildTurns(projectId, sessionId);
            Future<?> future = executor.submit(() -> run(projectId, sessionId, request, turns));
            synchronized (request) {
                request.future = future;
            }
        } catch (RejectedExecutionException exception) {
            pending.remove(sessionId, request);
            throw error(HttpStatus.TOO_MANY_REQUESTS, "AI_QUEUE_FULL",
                    "The AI queue is full; try again once a slot frees up.");
        }
        publishStatus(projectId, sessionId, "QUEUED", null);
        return new AskAccepted("QUEUED");
    }

    public PendingResource pendingState(UUID projectId, UUID sessionId) {
        chatService.requireAskTargetExists(projectId, sessionId);
        PendingRequest request = pending.get(sessionId);
        if (request == null) {
            throw error(HttpStatus.NOT_FOUND, "NOT_FOUND", "This session has no pending AI request.");
        }
        return new PendingResource(sessionId, request.state, request.questionMessageId);
    }

    /**
     * Cancels a QUEUED request (it leaves the queue and never answers). A RUNNING inference
     * cannot be interrupted; cancelling it returns 409. The start gate, not Future.cancel,
     * decides the outcome: a FutureTask still reads NEW while its body is executing.
     */
    public void cancel(UUID projectId, UUID sessionId) {
        chatService.requireAskTargetExists(projectId, sessionId);
        PendingRequest request = pending.get(sessionId);
        if (request == null) {
            throw error(HttpStatus.NOT_FOUND, "NOT_FOUND", "This session has no pending AI request.");
        }
        // First side to take the gate wins: either the worker starts the inference (409) or the
        // cancellation removes the request and the worker never answers (200).
        if (!request.startGate.compareAndSet(false, true)) {
            throw error(HttpStatus.CONFLICT, "AI_REQUEST_RUNNING",
                    "The inference already started and cannot be interrupted.");
        }
        Future<?> future;
        synchronized (request) {
            future = request.future;
        }
        if (future != null) {
            future.cancel(false);
            // Drop the cancelled task from the queue so its slot frees up for the next ask.
            executor.purge();
        }
        pending.remove(sessionId, request);
        request.state = "CANCELLED";
        publishStatus(projectId, sessionId, "CANCELLED", null);
    }

    private void run(UUID projectId, UUID sessionId, PendingRequest request,
            List<ChatAssistantProvider.Turn> turns) {
        // The cancellation may have taken the gate before this worker picked the task up.
        if (!request.startGate.compareAndSet(false, true)) {
            return;
        }
        request.state = "RUNNING";
        publishStatus(projectId, sessionId, "RUNNING", null);
        try {
            String answer = provider.chat(turns);
            chatService.postAiAnswer(sessionId, request.questionMessageId, answer);
            request.state = "COMPLETED";
            publishStatus(projectId, sessionId, "COMPLETED", null);
        } catch (RuntimeException exception) {
            logger.warn("AI answer failed for session {}", sessionId, exception);
            request.state = "FAILED";
            publishStatus(projectId, sessionId, "FAILED", briefReason(exception));
        } finally {
            // Two-arg remove: a newer request for the same session (none should exist while this
            // one is pending) must never be dropped by this worker's cleanup.
            pending.remove(sessionId, request);
        }
    }

    private List<ChatAssistantProvider.Turn> buildTurns(UUID projectId, UUID sessionId) {
        // S8-lite: the chosen diagram's semantic snapshot plus this session's recent messages;
        // real token budgeting, reservation and compaction stay in T6 (PD-03).
        ModelResource model = projectApiService.getModel(projectId);
        List<ChatAssistantProvider.Turn> turns = new ArrayList<>();
        turns.add(new ChatAssistantProvider.Turn("user",
                "Contexto actual del diagrama (JSON):\n" + contextRenderer.render(model)));
        List<MessageResource> history = chatService.sessionHistory(projectId, sessionId);
        int from = Math.max(0, history.size() - CONTEXT_HISTORY_MESSAGES);
        for (MessageResource message : history.subList(from, history.size())) {
            turns.add(new ChatAssistantProvider.Turn("assistant".equalsIgnoreCase(message.kind()) ? "assistant" : "user",
                    message.body()));
        }
        return turns;
    }

    private void publishStatus(UUID projectId, UUID sessionId, String state, String reason) {
        try {
            Map<String, Object> payload = reason == null
                    ? Map.of("sessionId", sessionId, "state", state)
                    : Map.of("sessionId", sessionId, "state", state, "reason", reason);
            eventBus.publish(ProjectEvent.of("chat.ai.status", projectId, payload));
        } catch (RuntimeException exception) {
            logger.warn("Failed to publish chat.ai.status {} for session {}", state, sessionId, exception);
        }
    }

    private static String briefReason(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static ApiException error(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message, List.of(), null);
    }
}
