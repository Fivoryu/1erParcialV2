package com.diagramador.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/** A selected notification recipient, independent of approvals. */
@Entity
@Table(name = "vcs_merge_request_reviewer", schema = "diagrammer")
public class VcsMergeRequestReviewer {
    @EmbeddedId
    private VcsMergeRequestApproval.Key id;

    @MapsId("mergeRequestId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merge_request_id", nullable = false)
    private VcsMergeRequest mergeRequest;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    protected VcsMergeRequestReviewer() {
    }

    public UserAccount getUser() {
        return user;
    }
}
