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
 * One memory of a diagram (row of {@code diagrammer.diagram_memory}, T5/L43). Memory is anchored
 * to the DiagramView and is independent of chat history: it cites its sources, keeps its kind and
 * lifecycle status, and survives history purges (CA-20).
 */
@Entity
@Table(name = "diagram_memory", schema = "diagrammer")
public class DiagramMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "memory_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "view_id", nullable = false)
    private DiagramView view;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, columnDefinition = "text")
    private MemoryKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "text")
    private MemoryStatus status;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_session_id")
    private ChatSession sourceSession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_message_id")
    private DiagramMessage sourceMessage;

    /** The accepted command this memory cites as evidence (PD-02/T5; nullable — message-sourced). */
    @Column(name = "source_command_id")
    private UUID sourceCommandId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by")
    private DiagramMemory replacedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private UserAccount createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected DiagramMemory() {
    }

    public static DiagramMemory create(DiagramView view, MemoryKind kind, String content,
            ChatSession sourceSession, DiagramMessage sourceMessage, UserAccount createdBy) {
        DiagramMemory memory = new DiagramMemory();
        memory.view = view;
        memory.kind = kind;
        memory.status = MemoryStatus.ACTIVE;
        memory.content = content;
        memory.sourceSession = sourceSession;
        memory.sourceMessage = sourceMessage;
        memory.createdBy = createdBy;
        memory.createdAt = OffsetDateTime.now();
        return memory;
    }

    public UUID getId() {
        return id;
    }

    public DiagramView getView() {
        return view;
    }

    public MemoryKind getKind() {
        return kind;
    }

    public MemoryStatus getStatus() {
        return status;
    }

    public String getContent() {
        return content;
    }

    public ChatSession getSourceSession() {
        return sourceSession;
    }

    public DiagramMessage getSourceMessage() {
        return sourceMessage;
    }

    public UUID getSourceCommandId() {
        return sourceCommandId;
    }

    public void setSourceCommandId(UUID sourceCommandId) {
        this.sourceCommandId = sourceCommandId;
    }

    public DiagramMemory getReplacedBy() {
        return replacedBy;
    }

    public void setStatus(MemoryStatus status) {
        this.status = status;
    }

    public void setReplacedBy(DiagramMemory replacedBy) {
        this.replacedBy = replacedBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
