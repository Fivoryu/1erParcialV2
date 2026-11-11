package com.diagramador.collaboration;

import com.diagramador.assistant.ChatAssistantProvider;
import com.diagramador.context.ContextBudgetService;
import com.diagramador.context.SpeakerLabel;
import com.diagramador.api.ApiException;
import com.diagramador.api.ChatResources.MessageResource;
import com.diagramador.domain.ChatSession;
import com.diagramador.identity.CurrentUser;
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
    private final com.diagramador.context.ContextBudgetService contextBudgetService;
    private final com.diagramador.persistence.ChatAttachmentRepository chatAttachmentRepository;
    private final com.diagramador.identity.CurrentUserContext currentUserContext;
    private final ProjectEventBus eventBus;
    private final DiagramMemoryService diagramMemoryService;
    private final ChatMemoryAgentService chatMemoryAgentService;
    private final boolean enabled;
    private final int imageMaxSide;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(MAX_QUEUE), runnable -> {
                Thread thread = new Thread(runnable, "chat-ai-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentMap<UUID, PendingRequest> pending = new ConcurrentHashMap<>();

    private static final class PendingRequest {
        private final UUID questionMessageId;
        private final UUID authorUserId;
        private final CurrentUser initiatingUser;
        private volatile Future<?> future;
        private volatile String state = "QUEUED";
        /**
         * Who wins the QUEUED→RUNNING transition. FutureTask states stay NEW while a task is
         * executing, so Future.cancel cannot tell "queued" from "running": the gate is the
         * authority. First side to CAS it owns the outcome (L38).
         */
        private final AtomicBoolean startGate = new AtomicBoolean(false);

        private PendingRequest(UUID questionMessageId, CurrentUser initiatingUser) {
            this.questionMessageId = questionMessageId;
            this.authorUserId = initiatingUser.userId();
            this.initiatingUser = initiatingUser;
        }
    }

    public record PendingResource(UUID sessionId, String state, UUID questionMessageId) {
    }

    public record AskAccepted(String state) {
    }

    public ChatAiService(ChatService chatService,
            com.diagramador.context.ContextBudgetService contextBudgetService,
            com.diagramador.persistence.ChatAttachmentRepository chatAttachmentRepository,
            com.diagramador.identity.CurrentUserContext currentUserContext,
            ProjectEventBus eventBus, DiagramMemoryService diagramMemoryService,
            ChatMemoryAgentService chatMemoryAgentService,
            @Value("${diagrammer.assistant.enabled:false}") boolean enabled,
            @Value("${diagrammer.assistant.image-max-side:768}") int imageMaxSide) {
        this.chatService = chatService;
        this.contextBudgetService = contextBudgetService;
        this.chatAttachmentRepository = chatAttachmentRepository;
        this.currentUserContext = currentUserContext;
        this.eventBus = eventBus;
        this.diagramMemoryService = diagramMemoryService;
        this.chatMemoryAgentService = chatMemoryAgentService;
        this.enabled = enabled;
        this.imageMaxSide = imageMaxSide;
    }

    /**
     * Enqueues one ask. Authorization and a baseline context-budget check run here; extraction,
     * fresh context construction and every conversational provider call run on the single worker.
     */
    public AskAccepted ask(UUID projectId, UUID sessionId, UUID messageId) {
        if (!enabled) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_DISABLED",
                    "The AI assistant is not enabled on this deployment.");
        }
        chatService.requireAskTarget(projectId, sessionId, messageId);
        CurrentUser initiatingUser = currentUserContext.require();
        UUID userId = initiatingUser.userId();
        // PD-04: "1 por usuario" — one pending ask per USER across all their sessions (L54).
        if (pending.values().stream().anyMatch(existing -> userId.equals(existing.authorUserId))) {
            throw error(HttpStatus.CONFLICT, "AI_REQUEST_IN_FLIGHT",
                    "This user already has a pending AI request.");
        }
        PendingRequest request = new PendingRequest(messageId, initiatingUser);
        if (pending.putIfAbsent(sessionId, request) != null) {
            throw error(HttpStatus.CONFLICT, "AI_REQUEST_IN_FLIGHT",
                    "This session already has a pending AI request.");
        }
        try {
            // QUEUED is published before any worker can exist, so RUNNING/COMPLETED can never
            // precede it, and a cancellation's CANCELLED can only follow it.
            publishStatus(projectId, sessionId, request, "QUEUED", null);
            contextBudgetService.prepare(projectId, sessionId, messageId);
            Future<?> future = executor.submit(() -> run(projectId, sessionId, request));
            synchronized (request) {
                request.future = future;
            }
            return new AskAccepted("QUEUED");
        } catch (RejectedExecutionException exception) {
            failRequest(projectId, sessionId, request, "The AI queue is full; try again once a slot frees up.");
            throw error(HttpStatus.TOO_MANY_REQUESTS, "AI_QUEUE_FULL",
                    "The AI queue is full; try again once a slot frees up.");
        } catch (ApiException exception) {
            // A typed client error (project/model resolution): clean the registry and let the
            // meaningful status reach the caller instead of masking it as a server failure.
            failRequest(projectId, sessionId, request, exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            logger.warn("AI ask could not be enqueued for session {}", sessionId, exception);
            failRequest(projectId, sessionId, request, briefReason(exception));
            throw error(HttpStatus.INTERNAL_SERVER_ERROR, "AI_REQUEST_FAILED",
                    "The AI request could not be enqueued.");
        }
    }

    /**
     * Compensates a QUEUED announcement with a FAILED state and clears the pending registry, so
     * no ask path can leave a session orphaned in QUEUED (verifier finding, L40).
     */
    private void failRequest(UUID projectId, UUID sessionId, PendingRequest request, String reason) {
        pending.remove(sessionId, request);
        request.state = "FAILED";
        publishStatus(projectId, sessionId, request, "FAILED", reason);
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
        publishStatus(projectId, sessionId, request, "CANCELLED", null);
    }

    private void run(UUID projectId, UUID sessionId, PendingRequest request) {
        // The cancellation may have taken the gate before this worker picked the task up.
        if (!request.startGate.compareAndSet(false, true)) {
            return;
        }
        CurrentUser previousUser = currentUserContext.optional().orElse(null);
        boolean previousOperator = currentUserContext.isOperator();
        try {
            currentUserContext.set(request.initiatingUser);
            request.state = "RUNNING";
            publishStatus(projectId, sessionId, request, "RUNNING", null);

            ChatSession accessibleSession = chatService.requireAccessibleSession(projectId, sessionId);
            UUID viewId = accessibleSession.getView().getId();
            try {
                // The existing bounded extractor consumes only the sources it actually sends.
                diagramMemoryService.extract(projectId, viewId);
            } catch (RuntimeException extractionFailure) {
                // Extraction is best-effort; the pending sources and the user's question remain intact.
                logger.warn("Automatic memory extraction failed for view {}", viewId, extractionFailure);
            }

            ChatSession freshSession = chatService.requireAccessibleSession(projectId, sessionId);
            ContextBudgetService.LiveContext live = contextBudgetService.prepare(projectId, sessionId,
                    request.questionMessageId);
            List<ChatAssistantProvider.Turn> turns = buildTurns(projectId, sessionId,
                    request.questionMessageId, live);
            String userQuestion = live.questionTurn() == null ? null : live.questionTurn().content();
            ChatMemoryAgentService.AgentAnswer answer = chatMemoryAgentService.answer(projectId,
                    freshSession.getView().getId(), live, turns, userQuestion, request.questionMessageId);

            boolean published;
            try {
                published = chatMemoryAgentService.publishIfSharedSourcesRemainAccessible(projectId, sessionId,
                        request.questionMessageId, answer);
            } catch (RuntimeException publicationFailure) {
                logger.warn("AI answer could not be safely published for session {}", sessionId, publicationFailure);
                published = false;
            }
            if (!published) {
                request.state = "FAILED";
                publishStatus(projectId, sessionId, request, "FAILED",
                        "The shared context changed; the response was not published.");
                return;
            }
            request.state = "COMPLETED";
            publishStatus(projectId, sessionId, request, "COMPLETED", null);
        } catch (RuntimeException exception) {
            logger.warn("AI answer failed for session {}", sessionId, exception);
            request.state = "FAILED";
            publishStatus(projectId, sessionId, request, "FAILED", briefReason(exception));
        } finally {
            pending.remove(sessionId, request);
            currentUserContext.clear();
            if (previousUser != null) {
                currentUserContext.set(previousUser);
            } else if (previousOperator) {
                currentUserContext.setOperator();
            }
        }
    }

    private List<ChatAssistantProvider.Turn> buildTurns(UUID projectId, UUID sessionId, UUID questionMessageId,
            ContextBudgetService.LiveContext live) {
        // T6/L45: the budget service meters, auto-compacts at ceil(0.8*P) and refuses overflow;
        // the built context carries fresh canonical UML, active memories and this session's history (CA-15).
        List<ChatAssistantProvider.Turn> turns = new ArrayList<>();
        turns.add(new ChatAssistantProvider.Turn("user", live.contextTurn()));
        turns.addAll(live.liveTurns());
        if (live.questionTurn() != null) {
            // T8/L49: the asked message's first image rides the question turn (the visual route).
            var questionAttachments = chatAttachmentRepository
                    .findByMessage_IdOrderByCreatedAtAscIdAsc(questionMessageId);
            if (questionAttachments.isEmpty()) {
                turns.add(new ChatAssistantProvider.Turn("user",
                        SpeakerLabel.attribute(live.questionSpeaker(), live.questionTurn().content())));
            } else {
                var image = questionAttachments.get(0);
                // Only the copy sent to the model is reduced; the stored attachment stays as uploaded.
                var fitted = ImageDownscaler.fit(image.getData(), image.getContentType(), imageMaxSide);
                turns.add(new ChatAssistantProvider.Turn("user",
                        SpeakerLabel.attribute(live.questionSpeaker(), live.questionTurn().content()),
                        java.util.Base64.getEncoder().encodeToString(fitted.data()),
                        fitted.mime()));
            }
        }
        return turns;
    }

    private void publishStatus(UUID projectId, UUID sessionId, PendingRequest request, String state,
            String reason) {
        try {
            // The requester is part of every status so each developer sees whom the AI is serving.
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("state", state);
            payload.put("requestedByDisplayName", request.initiatingUser.displayName());
            if (reason != null) {
                payload.put("reason", reason);
            }
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
