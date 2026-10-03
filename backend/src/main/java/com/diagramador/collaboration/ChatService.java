package com.diagramador.collaboration;

import com.diagramador.api.ApiException;
import com.diagramador.api.ChatResources.MessageResource;
import com.diagramador.domain.DiagramMessage;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.DiagramMessageRepository;
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

    private final ProjectRepository projectRepository;
    private final UmlModelRepository modelRepository;
    private final DiagramMessageRepository messageRepository;
    private final UserAccountRepository userAccountRepository;
    private final CurrentUserContext currentUserContext;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final ProjectEventBus projectEventBus;

    public ChatService(ProjectRepository projectRepository, UmlModelRepository modelRepository,
            DiagramMessageRepository messageRepository, UserAccountRepository userAccountRepository,
            CurrentUserContext currentUserContext, ProjectAuthorizationService projectAuthorizationService,
            ProjectEventBus projectEventBus) {
        this.projectRepository = projectRepository;
        this.modelRepository = modelRepository;
        this.messageRepository = messageRepository;
        this.userAccountRepository = userAccountRepository;
        this.currentUserContext = currentUserContext;
        this.projectAuthorizationService = projectAuthorizationService;
        this.projectEventBus = projectEventBus;
    }

    @Transactional(readOnly = true)
    public List<MessageResource> history(UUID projectId) {
        UmlModel model = requireMemberModel(projectId);
        return messageRepository.findByModel_IdOrderByCreatedAtAscIdAsc(model.getId()).stream()
                .map(ChatService::messageResource)
                .toList();
    }

    @Transactional
    public MessageResource post(UUID projectId, String body) {
        UmlModel model = requireMemberModel(projectId);
        String sanitizedBody = sanitizeBody(body);
        UUID authorId = currentUserContext.require().userId();
        UserAccount author = userAccountRepository.findById(authorId)
                .orElseThrow(() -> error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                        "The authenticated user account was not found."));

        DiagramMessage saved = messageRepository.saveAndFlush(DiagramMessage.create(model, author, sanitizedBody));
        MessageResource resource = messageResource(saved);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // Publish only after commit: subscribers must never see a message that could still roll back.
                    publishMessagePosted(projectId, resource);
                }
            });
        } else {
            // Registration is only legal inside a transaction; saveAndFlush has written the message, so publish now.
            publishMessagePosted(projectId, resource);
        }
        return resource;
    }

    private void publishMessagePosted(UUID projectId, MessageResource resource) {
        try {
            projectEventBus.publish(ProjectEvent.of("message.posted", projectId, Map.of("message", resource)));
        } catch (RuntimeException exception) {
            logger.warn("Failed to publish message.posted for message {}", resource.id(), exception);
            // The write is durable; delivery failure cannot undo it.
        }
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

    private static MessageResource messageResource(DiagramMessage message) {
        return new MessageResource(message.getId(), message.getAuthor().getId(), message.getAuthor().getDisplayName(),
                message.getBody(), message.getCreatedAt());
    }

    private static ApiException error(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message, List.of(), null);
    }
}
