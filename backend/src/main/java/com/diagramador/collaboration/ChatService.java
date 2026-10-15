package com.diagramador.collaboration;

import com.diagramador.api.ApiException;
import com.diagramador.api.ChatResources.ChatSessionResource;
import com.diagramador.api.ChatResources.MessageResource;
import com.diagramador.domain.ChatSession;
import com.diagramador.domain.DiagramMessage;
import com.diagramador.domain.DiagramView;
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
    private final UserAccountRepository userAccountRepository;
    private final CurrentUserContext currentUserContext;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final ProjectEventBus projectEventBus;

    public ChatService(ProjectRepository projectRepository, UmlModelRepository modelRepository,
            DiagramViewRepository viewRepository, ChatSessionRepository sessionRepository,
            DiagramMessageRepository messageRepository, UserAccountRepository userAccountRepository,
            CurrentUserContext currentUserContext, ProjectAuthorizationService projectAuthorizationService,
            ProjectEventBus projectEventBus) {
        this.projectRepository = projectRepository;
        this.modelRepository = modelRepository;
        this.viewRepository = viewRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
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
                .map(ChatService::messageResource)
                .toList();
    }

    @Transactional
    public MessageResource postToSession(UUID projectId, UUID sessionId, String body) {
        ChatSession session = requireSessionInProject(projectId, sessionId);
        return postMessage(session, body);
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
                .map(ChatService::messageResource)
                .toList();
    }

    @Transactional
    public MessageResource post(UUID projectId, String body) {
        UmlModel model = requireMemberModel(projectId);
        return postMessage(defaultSession(model), body);
    }

    // ---- Internals ----

    private MessageResource postMessage(ChatSession session, String body) {
        String sanitizedBody = sanitizeBody(body);
        UUID authorId = currentUserContext.require().userId();
        UserAccount author = userAccountRepository.findById(authorId)
                .orElseThrow(() -> error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                        "The authenticated user account was not found."));

        DiagramMessage saved = messageRepository.saveAndFlush(DiagramMessage.create(session, author, sanitizedBody));
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
            // The SSE channel stays project-scoped; the message resource carries its sessionId and kind.
            projectEventBus.publish(ProjectEvent.of("message.posted",
                    session.getView().getModel().getProject().getId(), Map.of("message", resource)));
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

    private static MessageResource messageResource(DiagramMessage message) {
        UserAccount author = message.getAuthor();
        return new MessageResource(message.getId(), message.getSession().getId(), message.getKind().name(),
                author == null ? null : author.getId(),
                author == null ? AI_DISPLAY_NAME : author.getDisplayName(),
                message.getBody(), message.getCreatedAt());
    }

    private static ApiException error(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message, List.of(), null);
    }
}
