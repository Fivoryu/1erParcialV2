package com.diagramador.domain;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Row of {@code diagrammer.vcs_merge_request}: a pull request between two branches of one diagram.
 *
 * <p>Conflicts are computed against the merge base at open time and frozen in
 * {@code conflicts}, so what the reviewer saw is what the merge acts on. A merge is refused
 * while the recorded conflict list is non-empty.
 */
@Entity
@Table(name = "vcs_merge_request", schema = "diagrammer")
public class VcsMergeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "merge_request_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_branch_id", nullable = false)
    private VcsBranch sourceBranch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_branch_id", nullable = false)
    private VcsBranch targetBranch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_commit_id", nullable = false)
    private VcsCommit sourceCommit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_commit_id", nullable = false)
    private VcsCommit targetCommit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merge_base_commit_id")
    private VcsCommit mergeBaseCommit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merged_commit_id")
    private VcsCommit mergedCommit;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "state", nullable = false)
    private VcsMergeState state;

    @Column(name = "has_conflicts", nullable = false)
    private boolean hasConflicts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conflicts")
    private JsonNode conflicts;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opened_by_user_id")
    private UserAccount openedByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_user_id")
    private UserAccount decidedByUser;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected VcsMergeRequest() {
    }

    public static VcsMergeRequest create(UmlModel model, VcsBranch sourceBranch, VcsBranch targetBranch,
            VcsCommit sourceCommit, VcsCommit targetCommit, VcsCommit mergeBaseCommit, String title,
            boolean hasConflicts, JsonNode conflicts, UserAccount openedByUser) {
        VcsMergeRequest request = new VcsMergeRequest();
        request.model = model;
        request.sourceBranch = sourceBranch;
        request.targetBranch = targetBranch;
        request.sourceCommit = sourceCommit;
        request.targetCommit = targetCommit;
        request.mergeBaseCommit = mergeBaseCommit;
        request.title = title;
        request.hasConflicts = hasConflicts;
        request.conflicts = conflicts;
        request.state = VcsMergeState.OPEN;
        request.openedByUser = openedByUser;
        request.updatedAt = OffsetDateTime.now();
        return request;
    }

    /** Recomputes the open request against the branches' current heads; only valid while OPEN. */
    public void refresh(VcsCommit sourceCommit, VcsCommit targetCommit, VcsCommit mergeBaseCommit,
            boolean hasConflicts, JsonNode conflicts) {
        this.sourceCommit = sourceCommit;
        this.targetCommit = targetCommit;
        this.mergeBaseCommit = mergeBaseCommit;
        this.hasConflicts = hasConflicts;
        this.conflicts = conflicts;
        this.updatedAt = OffsetDateTime.now();
    }

    public void markMerged(VcsCommit mergedCommit, UserAccount decidedByUser) {
        this.mergedCommit = mergedCommit;
        this.state = VcsMergeState.MERGED;
        this.decidedByUser = decidedByUser;
        this.updatedAt = OffsetDateTime.now();
    }

    public void markClosed(UserAccount decidedByUser) {
        this.state = VcsMergeState.CLOSED;
        this.decidedByUser = decidedByUser;
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public VcsBranch getSourceBranch() {
        return sourceBranch;
    }

    public VcsBranch getTargetBranch() {
        return targetBranch;
    }

    public VcsCommit getSourceCommit() {
        return sourceCommit;
    }

    public VcsCommit getTargetCommit() {
        return targetCommit;
    }

    public VcsCommit getMergeBaseCommit() {
        return mergeBaseCommit;
    }

    public VcsCommit getMergedCommit() {
        return mergedCommit;
    }

    public VcsMergeState getState() {
        return state;
    }

    public boolean hasConflicts() {
        return hasConflicts;
    }

    public JsonNode getConflicts() {
        return conflicts;
    }

    public String getTitle() {
        return title;
    }

    public UserAccount getOpenedByUser() {
        return openedByUser;
    }

    public UserAccount getDecidedByUser() {
        return decidedByUser;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
