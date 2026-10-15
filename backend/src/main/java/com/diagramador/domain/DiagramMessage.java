package com.diagramador.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Chat message row of {@code diagrammer.diagram_message}.
 */
@Entity
@Table(name = "diagram_message", schema = "diagrammer")
public class DiagramMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_user_id")
    private UserAccount author;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSession session;

    /**
     * The HUMAN question this message answers (T3/L38). Null for human messages; informational
     * only, so the DB nulls it out if the question is ever deleted.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reply_to_message_id")
    private DiagramMessage replyTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, columnDefinition = "text")
    private MessageKind kind;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    /**
     * Set by a successful memory extraction (T5/L43): the source is consumed exactly once, so a
     * forgotten memory's origin is never re-fed (CA-21).
     */
    @Column(name = "memory_extracted", nullable = false)
    private boolean memoryExtracted = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected DiagramMessage() {
    }

    public static DiagramMessage create(ChatSession session, UserAccount author, String body) {
        DiagramMessage message = new DiagramMessage();
        message.model = session.getView().getModel();
        message.author = author;
        message.session = session;
        message.kind = MessageKind.HUMAN;
        message.body = body;
        message.createdAt = OffsetDateTime.now();
        return message;
    }

    /**
     * AI answers have no human author and are linked to the question they respond to (L38).
     */
    public static DiagramMessage createAiAnswer(ChatSession session, DiagramMessage replyTo, String body) {
        DiagramMessage message = new DiagramMessage();
        message.model = session.getView().getModel();
        message.session = session;
        message.kind = MessageKind.AI;
        message.replyTo = replyTo;
        message.body = body;
        message.createdAt = OffsetDateTime.now();
        return message;
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public ChatSession getSession() {
        return session;
    }

    public MessageKind getKind() {
        return kind;
    }

    public UserAccount getAuthor() {
        return author;
    }

    public DiagramMessage getReplyTo() {
        return replyTo;
    }

    public String getBody() {
        return body;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean isMemoryExtracted() {
        return memoryExtracted;
    }

    public void setMemoryExtracted(boolean memoryExtracted) {
        this.memoryExtracted = memoryExtracted;
    }
}
