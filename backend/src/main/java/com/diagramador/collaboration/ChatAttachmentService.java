package com.diagramador.collaboration;

import com.diagramador.api.ApiException;
import com.diagramador.domain.ChatAttachment;
import com.diagramador.domain.ChatSession;
import com.diagramador.domain.UserAccount;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.persistence.ChatAttachmentRepository;
import com.diagramador.persistence.UserAccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Image attachments for the session chat (T8/L49). Uploads are validated by magic bytes and size
 * BEFORE any inference (CA-31); downloads are member-checked and session-scoped (CA-06). The
 * bytes live in the database (academic scope).
 */
@Service
public class ChatAttachmentService {

    private final ChatService chatService;
    private final ChatAttachmentRepository attachmentRepository;
    private final CurrentUserContext currentUserContext;
    private final UserAccountRepository userAccountRepository;
    private final long maxBytes;

    public record AttachmentResource(UUID attachmentId, String filename, long sizeBytes, String contentType) {
    }

    public record AttachmentBytes(String contentType, String filename, byte[] data) {
    }

    public ChatAttachmentService(ChatService chatService, ChatAttachmentRepository attachmentRepository,
            CurrentUserContext currentUserContext, UserAccountRepository userAccountRepository,
            @Value("${diagrammer.attachments.max-bytes:5242880}") long maxBytes) {
        this.chatService = chatService;
        this.attachmentRepository = attachmentRepository;
        this.currentUserContext = currentUserContext;
        this.userAccountRepository = userAccountRepository;
        this.maxBytes = maxBytes;
    }

    @Transactional
    public AttachmentResource upload(UUID projectId, UUID sessionId, String filename, byte[] bytes) {
        ChatSession session = chatService.requireAccessibleSession(projectId, sessionId);
        if (bytes == null || bytes.length == 0) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT", "The attached file is empty.");
        }
        if (bytes.length > maxBytes) {
            // CA-31: rejected before any inference; nothing else changes.
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_TOO_LARGE",
                    "The attached file exceeds the " + (maxBytes / (1024 * 1024)) + " MB limit.");
        }
        String contentType = detectContentType(bytes);
        if (contentType == null) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT",
                    "Only JPEG, PNG and WebP images are supported.");
        }
        UUID userId = currentUserContext.require().userId();
        UserAccount author = userAccountRepository.findById(userId).orElse(null);
        ChatAttachment attachment = attachmentRepository.saveAndFlush(ChatAttachment.create(
                session, filename == null || filename.isBlank() ? "image" : filename,
                contentType, bytes, sha256(bytes), author));
        return new AttachmentResource(attachment.getId(), attachment.getFilename(),
                attachment.getSizeBytes(), attachment.getContentType());
    }

    @Transactional(readOnly = true)
    public AttachmentBytes bytesFor(UUID projectId, UUID sessionId, UUID attachmentId) {
        ChatSession session = chatService.requireAccessibleSession(projectId, sessionId);
        ChatAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "The attachment was not found for this diagram."));
        if (!attachment.getSession().getId().equals(session.getId())) {
            // A foreign attachment is indistinguishable from a missing one (CA-06).
            throw error(HttpStatus.NOT_FOUND, "NOT_FOUND", "The attachment was not found for this diagram.");
        }
        return new AttachmentBytes(attachment.getContentType(), attachment.getFilename(), attachment.getData());
    }

    /**
     * Validates the attachment ids handed to a message post: all must exist, belong to the
     * session and still be unbound (CA-06); at most maxPerMessage per message (L49).
     */
    @Transactional(readOnly = true)
    public void validateForPost(UUID sessionId, List<UUID> attachmentIds, int maxPerMessage) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        if (attachmentIds.size() > maxPerMessage) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT",
                    "At most " + maxPerMessage + " attachments per message.");
        }
        for (UUID attachmentId : attachmentIds) {
            ChatAttachment attachment = attachmentRepository.findById(attachmentId)
                    .orElseThrow(() -> error(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT",
                            "The attachment was not found for this session."));
            if (!attachment.getSession().getId().equals(sessionId) || attachment.getMessage() != null) {
                // A foreign or already-bound attachment cannot ride this message (CA-06).
                throw error(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT",
                        "The attachment was not found for this session.");
            }
        }
    }

    @Transactional
    public void bindToMessage(com.diagramador.domain.DiagramMessage message, List<UUID> attachmentIds) {
        if (attachmentIds == null) {
            return;
        }
        for (UUID attachmentId : attachmentIds) {
            attachmentRepository.findById(attachmentId).ifPresent(attachment -> attachment.setMessage(message));
        }
    }

    /** The attachments bound to one message (for the resource mapping). */
    @Transactional(readOnly = true)
    public List<UUID> attachmentsOf(UUID messageId) {
        return attachmentRepository.findByMessage_IdOrderByCreatedAtAscIdAsc(messageId)
                .stream().map(ChatAttachment::getId).toList();
    }

    /** Image cost (tokens) of the attachments bound to one message (CA-23). */
    public int imageCount(UUID messageId) {
        return attachmentRepository.findByMessage_IdOrderByCreatedAtAscIdAsc(messageId).size();
    }

    private static String detectContentType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private static ApiException error(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message, List.of(), null);
    }
}
