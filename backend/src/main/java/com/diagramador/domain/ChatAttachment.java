package com.diagramador.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One image attachment of a chat session (row of {@code diagrammer.chat_attachment}, T8/L49).
 * The bytes live in the database, validated by magic bytes at upload (CA-31); the attachment is
 * born bound to its session and links to the message that carries it once posted.
 */
@Entity
@Table(name = "chat_attachment", schema = "diagrammer")
public class ChatAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    private DiagramMessage message;

    @Column(name = "filename", nullable = false)
    private String filename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "data", nullable = false, columnDefinition = "bytea")
    private byte[] data;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private UserAccount createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ChatAttachment() {
    }

    public static ChatAttachment create(ChatSession session, String filename, String contentType,
            byte[] data, String sha256, UserAccount createdBy) {
        ChatAttachment attachment = new ChatAttachment();
        attachment.session = session;
        attachment.filename = filename;
        attachment.contentType = contentType;
        attachment.sizeBytes = data.length;
        attachment.data = data;
        attachment.sha256 = sha256;
        attachment.createdBy = createdBy;
        attachment.createdAt = OffsetDateTime.now();
        return attachment;
    }

    public UUID getId() {
        return id;
    }

    public ChatSession getSession() {
        return session;
    }

    public DiagramMessage getMessage() {
        return message;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public byte[] getData() {
        return data;
    }

    public String getSha256() {
        return sha256;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setMessage(DiagramMessage message) {
        this.message = message;
    }
}
