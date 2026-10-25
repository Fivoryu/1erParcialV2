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
 * Row of {@code diagrammer.vcs_branch}: a named pointer to the last commit of a line of work.
 *
 * <p>{@code main} is created with the model and is the branch the backend generator reads.
 * A protected branch only accepts direct commits from the diagram owner, the platform
 * operator, or a user explicitly granted {@code COMMIT} on that diagram.
 */
@Entity
@Table(name = "vcs_branch", schema = "diagrammer")
public class VcsBranch {

    public static final String MAIN = "main";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "head_commit_id", nullable = false)
    private VcsCommit headCommit;

    @Column(name = "is_protected", nullable = false)
    private boolean isProtected;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private UserAccount createdByUser;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected VcsBranch() {
    }

    public static VcsBranch create(UmlModel model, String name, VcsCommit headCommit, boolean isProtected,
            UserAccount createdByUser) {
        VcsBranch branch = new VcsBranch();
        branch.model = model;
        branch.name = name;
        branch.headCommit = headCommit;
        branch.isProtected = isProtected;
        branch.createdByUser = createdByUser;
        branch.updatedAt = OffsetDateTime.now();
        return branch;
    }

    public void moveHeadTo(VcsCommit headCommit) {
        this.headCommit = headCommit;
        this.updatedAt = OffsetDateTime.now();
    }

    public void setProtected(boolean isProtected) {
        this.isProtected = isProtected;
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public String getName() {
        return name;
    }

    public VcsCommit getHeadCommit() {
        return headCommit;
    }

    public boolean isProtected() {
        return isProtected;
    }

    public UserAccount getCreatedByUser() {
        return createdByUser;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
