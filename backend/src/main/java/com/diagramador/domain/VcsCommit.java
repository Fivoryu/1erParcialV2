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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Row of {@code diagrammer.vcs_commit}: an explicit, authored commit of one diagram state.
 *
 * <p>The autosave writes the working copy and never creates a commit, so every row here is a
 * deliberate act with a human (or AI-served user) behind it. A merge commit has a second
 * parent; every other commit has exactly one.
 */
@Entity
@Table(name = "vcs_commit", schema = "diagrammer")
public class VcsCommit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "commit_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_commit_id")
    private VcsCommit parent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "second_parent_commit_id")
    private VcsCommit secondParent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private VcsSnapshot snapshot;

    @Column(name = "branch_name", nullable = false, length = 120)
    private String branchName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "kind", nullable = false)
    private VcsCommitKind kind;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "origin", nullable = false)
    private VcsCommitOrigin origin;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_user_id")
    private UserAccount author;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected VcsCommit() {
    }

    public static VcsCommit create(UmlModel model, VcsCommit parent, VcsCommit secondParent, VcsSnapshot snapshot,
            String branchName, VcsCommitKind kind, VcsCommitOrigin origin, String message, UserAccount author) {
        VcsCommit commit = new VcsCommit();
        commit.model = model;
        commit.parent = parent;
        commit.secondParent = secondParent;
        commit.snapshot = snapshot;
        commit.branchName = branchName;
        commit.kind = kind;
        commit.origin = origin;
        commit.message = message;
        commit.author = author;
        return commit;
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public VcsCommit getParent() {
        return parent;
    }

    public VcsCommit getSecondParent() {
        return secondParent;
    }

    public VcsSnapshot getSnapshot() {
        return snapshot;
    }

    public String getBranchName() {
        return branchName;
    }

    public VcsCommitKind getKind() {
        return kind;
    }

    public VcsCommitOrigin getOrigin() {
        return origin;
    }

    public String getMessage() {
        return message;
    }

    public UserAccount getAuthor() {
        return author;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
