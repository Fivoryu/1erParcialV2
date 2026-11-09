package com.diagramador.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** An immutable request-changes review, independent of approval. */
@Entity
@Table(name = "vcs_merge_request_review", schema = "diagrammer")
public class VcsMergeRequestReview {
    @Id
    @Column(name = "review_id")
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merge_request_id", nullable = false)
    private VcsMergeRequest mergeRequest;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;
    @Column(nullable = false, length = 2000)
    private String message;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected VcsMergeRequestReview() { }

    public static VcsMergeRequestReview create(VcsMergeRequest request, UserAccount user, String message) {
        VcsMergeRequestReview review = new VcsMergeRequestReview();
        review.id = UUID.randomUUID();
        review.mergeRequest = request;
        review.user = user;
        review.message = message;
        review.createdAt = OffsetDateTime.now();
        return review;
    }

    public UUID getId() { return id; }
    public UserAccount getUser() { return user; }
    public String getMessage() { return message; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
