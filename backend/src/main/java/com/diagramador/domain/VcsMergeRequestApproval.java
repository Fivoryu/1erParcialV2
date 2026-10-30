package com.diagramador.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Row of {@code diagrammer.vcs_merge_request_approval}: one reviewer's approval of one merge request. */
@Entity
@Table(name = "vcs_merge_request_approval", schema = "diagrammer")
public class VcsMergeRequestApproval {

    @EmbeddedId
    private Key id;

    @MapsId("mergeRequestId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merge_request_id", nullable = false)
    private VcsMergeRequest mergeRequest;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(name = "approved_at", nullable = false)
    private OffsetDateTime approvedAt;

    protected VcsMergeRequestApproval() {
    }

    public static VcsMergeRequestApproval create(VcsMergeRequest mergeRequest, UserAccount user) {
        VcsMergeRequestApproval approval = new VcsMergeRequestApproval();
        approval.id = new Key(mergeRequest.getId(), user.getId());
        approval.mergeRequest = mergeRequest;
        approval.user = user;
        approval.approvedAt = OffsetDateTime.now();
        return approval;
    }

    public Key getId() {
        return id;
    }

    public VcsMergeRequest getMergeRequest() {
        return mergeRequest;
    }

    public UserAccount getUser() {
        return user;
    }

    public OffsetDateTime getApprovedAt() {
        return approvedAt;
    }

    /** Composite primary key (merge request, reviewer). */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "merge_request_id", nullable = false)
        private UUID mergeRequestId;

        @Column(name = "user_id", nullable = false)
        private UUID userId;

        protected Key() {
        }

        public Key(UUID mergeRequestId, UUID userId) {
            this.mergeRequestId = mergeRequestId;
            this.userId = userId;
        }

        public UUID getMergeRequestId() {
            return mergeRequestId;
        }

        public UUID getUserId() {
            return userId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(mergeRequestId, that.mergeRequestId) && Objects.equals(userId, that.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mergeRequestId, userId);
        }
    }
}
