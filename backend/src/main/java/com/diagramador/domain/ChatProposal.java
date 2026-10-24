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
 * Row of {@code diagrammer.chat_proposal}: a plan suggested in a chat session. It stays PENDING
 * until an editor decides; ACCEPTED means the plan went through the assistant apply path, FAILED
 * that the apply was attempted and stopped.
 */
@Entity
@Table(name = "chat_proposal", schema = "diagrammer")
public class ChatProposal {

    public static final String PENDING = "PENDING";
    public static final String APPLYING = "APPLYING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "proposal_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false, updatable = false)
    private DiagramMessage message;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private ChatSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_user_id", nullable = false, updatable = false)
    private UserAccount requestedBy;

    @Column(name = "utterance", nullable = false, columnDefinition = "text", updatable = false)
    private String utterance;

    @Column(name = "operations", nullable = false, columnDefinition = "text", updatable = false)
    private String operations;

    @Column(name = "base_revision", nullable = false, updatable = false)
    private long baseRevision;

    @Column(name = "status", nullable = false, columnDefinition = "text")
    private String status = PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_user_id")
    private UserAccount decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "result_summary", columnDefinition = "text")
    private String resultSummary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ChatProposal() {
    }

    public static ChatProposal create(DiagramMessage message, UserAccount requestedBy, String utterance,
            String operations, long baseRevision) {
        ChatProposal proposal = new ChatProposal();
        proposal.message = message;
        proposal.session = message.getSession();
        proposal.requestedBy = requestedBy;
        proposal.utterance = utterance;
        proposal.operations = operations;
        proposal.baseRevision = baseRevision;
        proposal.createdAt = OffsetDateTime.now();
        return proposal;
    }

    /** Records the decision; a proposal is decided exactly once. */
    public void decide(String newStatus, UserAccount decidedBy, String resultSummary) {
        this.status = newStatus;
        this.decidedBy = decidedBy;
        this.decidedAt = OffsetDateTime.now();
        this.resultSummary = resultSummary;
    }

    public UUID getId() {
        return id;
    }

    public DiagramMessage getMessage() {
        return message;
    }

    public ChatSession getSession() {
        return session;
    }

    public UserAccount getRequestedBy() {
        return requestedBy;
    }

    public String getUtterance() {
        return utterance;
    }

    public String getOperations() {
        return operations;
    }

    public long getBaseRevision() {
        return baseRevision;
    }

    public String getStatus() {
        return status;
    }

    public UserAccount getDecidedBy() {
        return decidedBy;
    }

    public OffsetDateTime getDecidedAt() {
        return decidedAt;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
