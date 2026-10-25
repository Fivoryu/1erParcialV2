package com.diagramador.collaboration;

import com.diagramador.api.ApiException;
import com.diagramador.api.ChatResources.ChatSessionResource;
import com.diagramador.api.ChatResources.MessageResource;
import com.diagramador.domain.ChatAttachment;
import com.diagramador.domain.ChatSession;
import com.diagramador.domain.DiagramMessage;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.MessageKind;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.ChatSessionRepository;
import com.diagramador.persistence.DiagramMessageRepository;
import com.diagramador.persistence.DiagramViewRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UserAccountRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_BODY_LENGTH = 2000;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final String DEFAULT_SESSION_TITLE = "General";
    private static final String AI_DISPLAY_NAME = "Asistente";

    private final ProjectRepository projectRepository;
    private final UmlModelRepository modelRepository;
    private final DiagramViewRepository viewRepository;
    private final ChatSessionRepository sessionRepository;
    private final DiagramMessageRepository messageRepository;
    private final com.diagramador.persistence.ChatAttachmentRepository chatAttachmentRepository;
    private final com.diagramador.persistence.ChatSessionCompactionRepository compactionRepository;
    private final UserAccountRepository userAccountRepository;
    private final CurrentUserContext currentUserContext;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final ProjectEventBus projectEventBus;

    public ChatService(ProjectRepository projectRepository, UmlModelRepository modelRepository,
            DiagramViewRepository viewRepository, ChatSessionRepository sessionRepository,
            DiagramMessageRepository messageRepository,
            com.diagramador.persistence.ChatAttachmentRepository chatAttachmentRepository,
            com.diagramador.persistence.ChatSessionCompactionRepository compactionRepository,
            UserAccountRepository userAccountRepository,
            CurrentUserContext currentUserContext, ProjectAuthorizationService projectAuthorizationService,
            ProjectEventBus projectEventBus) {
        this.projectRepository = projectRepository;
        this.modelRepository = modelRepository;
        this.viewRepository = viewRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.chatAttachmentRepository = chatAttachmentRepository;
        this.compactionRepository = compactionRepository;
        this.userAccountRepository = userAccountRepository;
        this.currentUserContext = currentUserContext;
        this.projectAuthorizationService = projectAuthorizationService;
        this.projectEventBus = projectEventBus;
    }

    // ---- Sessions (PD-02/L33) ----

    @Transactional
    public ChatSessionResource createSession(UUID projectId, UUID viewId, String title) {
        DiagramView view = requireViewInProject(projectId, viewId);
        ChatSession session = sessionRepository.saveAndFlush(ChatSession.create(view,
                currentUser(), sanitizeTitle(title), false));
        return sessionResource(session);
    }

    @Transactional(readOnly = true)
    public List<ChatSessionResource> sessions(UUID projectId, UUID viewId) {
        DiagramView view = requireViewInProject(projectId, viewId);
        return sessionRepository.findByView_IdOrderByCreatedAtAscIdAsc(view.getId()).stream()
                .map(ChatService::sessionResource)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MessageResource> sessionHistory(UUID projectId, UUID sessionId) {
        ChatSession session = requireSessionInProject(projectId, sessionId);
        return messageRepository.findBySession_IdOrderByCreatedAtAscIdAsc(session.getId()).stream()
                .map(this::messageResource)
                .toList();
    }

    @Transactional
    public MessageResource postToSession(UUID projectId, UUID sessionId, String body) {
        return postToSession(projectId, sessionId, body, null);
    }

    /**
     * Posts with image attachments (T8/L49): every attachment must exist, belong to this session
     * and still be unbound (CA-06), and the per-message cap holds (CA-31). The link happens in
     * the same transaction, so a rejected attachment leaves no orphan message.
     */
    @Transactional
    public MessageResource postToSession(UUID projectId, UUID sessionId, String body,
            List<UUID> attachmentIds) {
        ChatSession session = requireSessionInProject(projectId, sessionId);
        List<ChatAttachment> attachments = validateAttachments(session, attachmentIds);
        DiagramMessage saved = postMessageInternal(session, body);
        attachments.forEach(attachment -> attachment.setMessage(saved));
        MessageResource resource = messageResource(saved);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // T10/L54: session messages are published over SSE exactly like the legacy
                    // route — collaborators must not need a reload (CA-04).
                    publishMessagePosted(session, resource);
                }
            });
        } else {
            publishMessagePosted(session, resource);
        }
        return resource;
    }

    private List<ChatAttachment> validateAttachments(ChatSession session, List<UUID> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }
        if (attachmentIds.size() > 3) {
            // CA-31/L49: at most three images per message.
            throw error(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT", "At most 3 attachments per message.");
        }
        List<ChatAttachment> found = new ArrayList<>();
        for (UUID attachmentId : attachmentIds) {
            ChatAttachment attachment = chatAttachmentRepository.findById(attachmentId)
                    .orElseThrow(() -> error(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT",
                            "The attachment was not found for this session."));
            if (!attachment.getSession().getId().equals(session.getId()) || attachment.getMessage() != null) {
                // A foreign or already-bound attachment cannot ride this message (CA-06).
                throw error(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT",
                        "The attachment was not found for this session.");
            }
            found.add(attachment);
        }
        return found;
    }

    /**
     * PD-06 (L34): a physical delete of only this session's messages, reserved to OWNER. The
     * session row survives empty and reusable; other sessions and any derived knowledge are
     * untouched, and the response reports the effect instead of promising a forensic purge.
     */
    @Transactional
    public Map<String, Long> deleteSessionHistory(UUID projectId, UUID sessionId) {
        requireProject(projectId);
        // Reading/conversing is not enough: only administration (OWNER) deletes history (PD-01).
        projectAuthorizationService.requireOwner(projectId);
        ChatSession session = requireSessionInProject(projectId, sessionId);
        long deleted = messageRepository.deleteBySession_Id(session.getId());
        // L54: the compacted summary belongs to the session's history — purging the history drops
        // it too, or the old content keeps flowing into the context after the purge (CA-20).
        compactionRepository.deleteBySession_Id(session.getId());
        return Map.of("deletedMessages", deleted);
    }

    // ---- Legacy project-level chat (compatibility; delegates to the default session) ----

    /**
     * The legacy endpoints keep serving the model's default ("General") session so the current
     * chat panel works unchanged until T9 replaces it. Creating the session on demand is why this
     * read is a regular transaction; V15 backfilled every model that existed before it.
     */
    @Transactional
    public List<MessageResource> history(UUID projectId) {
        UmlModel model = requireMemberModel(projectId);
        ChatSession general = defaultSession(model);
        return messageRepository.findBySession_IdOrderByCreatedAtAscIdAsc(general.getId()).stream()
                .map(this::messageResource)
                .toList();
    }

    @Transactional
    public MessageResource post(UUID projectId, String body) {
        UmlModel model = requireMemberModel(projectId);
        return postMessage(defaultSession(model), body);
    }

    // ---- AI asks and answers (T3/L38) ----

    /**
     * Validates an ask target without posting anything: the session must belong to the project
     * (404 otherwise, CA-06) and the referenced message must be a HUMAN message of that very
     * session (400 otherwise). Runs in the request thread, so member authorization inside
     * requireSessionInProject applies.
     */
    @Transactional(readOnly = true)
    public void requireAskTarget(UUID projectId, UUID sessionId, UUID messageId) {
        ChatSession session = requireSessionInProject(projectId, sessionId);
        DiagramMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "The message was not found for this session."));
        if (!message.getSession().getId().equals(session.getId())
                || message.getKind() != MessageKind.HUMAN) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD",
                    "Only a human message of this session can be asked to the assistant.");
        }
    }

    /**
     * Session-chain validation alone, for endpoints that only need to confirm the session is
     * reachable and belongs to the project (GET/DELETE of the pending AI request, L38).
     */
    @Transactional(readOnly = true)
    public void requireAskTargetExists(UUID projectId, UUID sessionId) {
        requireSessionInProject(projectId, sessionId);
    }

    /**
     * Persists the AI answer on the queue worker thread. Deliberately free of member
     * authorization: the ask was already authorized in the request thread, and the worker has no
     * user context. The answer is an AI-kind message linked to its question (L38).
     */
    @Transactional
    public MessageResource postAiAnswer(UUID sessionId, UUID replyToMessageId, String body) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(ChatService::notFoundForProject);
        DiagramMessage question = messageRepository.findById(replyToMessageId)
                .orElseThrow(ChatService::notFoundForProject);
        if (!question.getSession().getId().equals(session.getId())) {
            throw error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "The answered question does not belong to the session.");
        }
        DiagramMessage saved = messageRepository.saveAndFlush(
                DiagramMessage.createAiAnswer(session, question, sanitizeBody(body)));
        MessageResource resource = messageResource(saved);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishMessagePosted(session, resource);
                }
            });
        } else {
            publishMessagePosted(session, resource);
        }
        return resource;
    }

    // ---- Proposals (V25) ----

    /**
     * Saves the PROPOSAL card of a chat suggestion. It joins the caller's transaction so the card
     * and its {@code chat_proposal} row commit together; {@link #announceAfterCommit} publishes it.
     */
    @Transactional
    public DiagramMessage savePendingProposalMessage(UUID projectId, UUID sessionId, String body) {
        ChatSession session = requireSessionInProject(projectId, sessionId);
        return messageRepository.saveAndFlush(
                DiagramMessage.createProposal(session, currentUser(), sanitizeBody(body)));
    }

    /** Publishes a saved message once the surrounding transaction commits. */
    public MessageResource announceAfterCommit(DiagramMessage saved) {
        MessageResource resource = messageResource(saved);
        ChatSession session = saved.getSession();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishMessagePosted(session, resource);
                }
            });
        } else {
            publishMessagePosted(session, resource);
        }
        return resource;
    }

    // ---- Internals ----

    private DiagramMessage postMessageInternal(ChatSession session, String body) {
        String sanitizedBody = sanitizeBody(body);
        UUID authorId = currentUserContext.require().userId();
        UserAccount author = userAccountRepository.findById(authorId)
                .orElseThrow(() -> error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                        "The authenticated user account was not found."));

        return messageRepository.saveAndFlush(DiagramMessage.create(session, author, sanitizedBody));
    }

    private MessageResource postMessage(ChatSession session, String body) {
        DiagramMessage saved = postMessageInternal(session, body);
        MessageResource resource = messageResource(saved);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // Publish only after commit: subscribers must never see a message that could still roll back.
                    publishMessagePosted(session, resource);
                }
            });
        } else {
            // Registration is only legal inside a transaction; saveAndFlush has written the message, so publish now.
            publishMessagePosted(session, resource);
        }
        return resource;
    }

    private void publishMessagePosted(ChatSession session, MessageResource resource) {
        try {
            // The SSE channel stays project-scoped; the payload carries the message resource plus
            // explicit sessionId/kind so clients can filter per session (L33/L38).
            projectEventBus.publish(ProjectEvent.of("message.posted",
                    session.getView().getModel().getProject().getId(),
                    Map.of("message", resource, "sessionId", resource.sessionId(), "kind", resource.kind())));
        } catch (RuntimeException exception) {
            logger.warn("Failed to publish message.posted for message {}", resource.id(), exception);
            // The write is durable; delivery failure cannot undo it.
        }
    }

    private ChatSession defaultSession(UmlModel model) {
        return sessionRepository.findByViewModelIdAndIsDefaultTrue(model.getId())
                .orElseGet(() -> {
                    DiagramView defaultView = viewRepository.findByModelIdAndIsDefaultTrue(model.getId())
                            .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND",
                                    "The default view was not found for this project."));
                    return sessionRepository.saveAndFlush(ChatSession.create(defaultView, currentUser(),
                            DEFAULT_SESSION_TITLE, true));
                });
    }

    private DiagramView requireViewInProject(UUID projectId, UUID viewId) {
        UmlModel model = requireMemberModel(projectId);
        DiagramView view = viewRepository.findById(viewId)
                .orElseThrow(() -> notFoundForProject());
        if (!view.getModel().getId().equals(model.getId())) {
            // A view of another project is indistinguishable from a missing one (CA-06).
            throw notFoundForProject();
        }
        return view;
    }

    /**
     * Member-checked session resolution shared with sibling services (T6 meter/compaction): a
     * session of another project is indistinguishable from a missing one (CA-06).
     */
    public ChatSession requireAccessibleSession(UUID projectId, UUID sessionId) {
        return requireSessionInProject(projectId, sessionId);
    }

    /**
     * Member-checked view resolution shared with sibling services (T5 memory): a view of another
     * project is indistinguishable from a missing one (CA-06).
     */
    public DiagramView requireAccessibleView(UUID projectId, UUID viewId) {
        return requireViewInProject(projectId, viewId);
    }

    private ChatSession requireSessionInProject(UUID projectId, UUID sessionId) {
        UmlModel model = requireMemberModel(projectId);
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> notFoundForProject());
        if (!session.getView().getModel().getId().equals(model.getId())) {
            throw notFoundForProject();
        }
        return session;
    }

    private UmlModel requireMemberModel(UUID projectId) {
        requireProject(projectId);
        UmlModel model = modelRepository.findByProjectId(projectId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "The model was not found for this project."));
        // Chat is a conversation, not model editing, so VIEWER members may both read and post.
        projectAuthorizationService.requireMember(projectId);
        return model;
    }

    private void requireProject(UUID projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "The project was not found."));
    }

    private UserAccount currentUser() {
        UUID userId = currentUserContext.require().userId();
        return userAccountRepository.findById(userId)
                .orElseThrow(() -> error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                        "The authenticated user account was not found."));
    }

    private static ApiException notFoundForProject() {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "The session was not found for this project.");
    }

    private static String sanitizeBody(String body) {
        // The server stores plain text and the client renders it as text (React escapes it), so escaping is a client
        // guarantee, not something the server pretends to do. HTML sanitization is deliberately not performed.
        String trimmed = body == null ? "" : body.strip();
        if (trimmed.isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", "A non-blank body is required.");
        }
        if (trimmed.length() > MAX_BODY_LENGTH) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD",
                    "A message body must not exceed 2000 characters.");
        }
        return trimmed;
    }

    private static String sanitizeTitle(String title) {
        String trimmed = title == null ? "" : title.strip();
        if (trimmed.isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", "A non-blank title is required.");
        }
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD",
                    "A session title must not exceed 200 characters.");
        }
        return trimmed;
    }

    private static ChatSessionResource sessionResource(ChatSession session) {
        return new ChatSessionResource(session.getId(), session.getView().getId(), session.getTitle(),
                session.getCreatedBy().getId(), session.getCreatedAt());
    }

    private MessageResource messageResource(DiagramMessage message) {
        UserAccount author = message.getAuthor();
        DiagramMessage replyTo = message.getReplyTo();
        List<UUID> attachments = chatAttachmentRepository
                .findByMessage_IdOrderByCreatedAtAscIdAsc(message.getId())
                .stream().map(ChatAttachment::getId).toList();
        return new MessageResource(message.getId(), message.getSession().getId(), message.getKind().name(),
                author == null ? null : author.getId(),
                author == null ? AI_DISPLAY_NAME : author.getDisplayName(),
                message.getBody(), message.getCreatedAt(), replyTo == null ? null : replyTo.getId(), attachments);
    }

    private static ApiException error(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message, List.of(), null);
    }
}
