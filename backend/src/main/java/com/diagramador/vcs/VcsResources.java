package com.diagramador.vcs;

import com.diagramador.api.ModelResource;
import com.diagramador.domain.VcsCommitKind;
import com.diagramador.domain.VcsAction;
import com.diagramador.domain.VcsCommitOrigin;
import com.diagramador.domain.VcsMergeState;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** JSON resources for the frozen version-control HTTP contract. */
public final class VcsResources {

    private VcsResources() {
    }

    public record Status(String branch, UUID headCommitId, boolean dirty) {
    }

    public record Branch(String name, boolean isProtected, UUID headCommitId, String headMessage,
            String headAuthorDisplayName, OffsetDateTime headCreatedAt) {
    }

    public record Commit(UUID commitId, String branchName, VcsCommitKind kind, VcsCommitOrigin origin,
            String message, UUID authorUserId, String authorDisplayName, UUID parentCommitId,
            UUID secondParentCommitId, String stateHash, OffsetDateTime createdAt) {
    }

    public record Change(String changeKind, String targetType, UUID targetId, String targetName) {
    }

    /** A field that differs between two snapshots; values are already formatted for display. */
    public record FieldChange(String field, String before, String after) {
    }

    /** Field-level change of one element (classifier, attribute, operation, relationship...) between two snapshots. */
    public record ElementChange(String changeKind, String targetType, UUID targetId, String targetName,
            String ownerName, List<FieldChange> fields) {
        public ElementChange {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    /** A person credited on a commit: AUTHOR, or for a merge commit MERGED_BY plus one CONTRIBUTOR per merged author. */
    public record CommitAuthor(UUID userId, String displayName, String role) {
    }

    public record CommitDetail(UUID commitId, String branchName, VcsCommitKind kind, VcsCommitOrigin origin,
            String message, UUID authorUserId, String authorDisplayName, UUID parentCommitId,
            UUID secondParentCommitId, String stateHash, OffsetDateTime createdAt, List<Change> changes,
            List<ElementChange> details, List<CommitAuthor> authors) {
        public CommitDetail {
            changes = changes == null ? List.of() : List.copyOf(changes);
            details = details == null ? List.of() : List.copyOf(details);
            authors = authors == null ? List.of() : List.copyOf(authors);
        }
    }

    public record Checkout(String branch, ModelResource model) {
    }

    public record MergeRequest(UUID mergeRequestId, String title, VcsMergeState state,
            String sourceBranch, String targetBranch, UUID sourceCommitId, UUID targetCommitId,
            UUID mergeBaseCommitId, boolean hasConflicts, JsonNode conflicts, String openedByDisplayName,
            String decidedByDisplayName, OffsetDateTime createdAt) {
    }

    /** Two commits compared: field-level changes, the commits the target has that the start lacks, and their authors. */
    public record CommitComparison(List<ElementChange> changes, List<Commit> commits,
            List<CommitAuthor> contributors) {
        public CommitComparison {
            changes = changes == null ? List.of() : List.copyOf(changes);
            commits = commits == null ? List.of() : List.copyOf(commits);
            contributors = contributors == null ? List.of() : List.copyOf(contributors);
        }
    }

    /**
     * What a merge request brings: field-level changes, the commits it adds, the people who made them and, for an
     * open request, the conflicts against the target as it is now.
     */
    public record MergeRequestDiff(List<ElementChange> changes, List<Commit> commits,
            List<CommitAuthor> contributors, boolean hasConflicts, JsonNode conflicts) {
        public MergeRequestDiff {
            changes = changes == null ? List.of() : List.copyOf(changes);
            commits = commits == null ? List.of() : List.copyOf(commits);
            contributors = contributors == null ? List.of() : List.copyOf(contributors);
        }
    }

    public record Permission(UUID userId, String username, String displayName, List<VcsAction> actions) {
        public Permission {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }
}
