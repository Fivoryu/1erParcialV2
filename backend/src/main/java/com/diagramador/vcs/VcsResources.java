package com.diagramador.vcs;

import com.diagramador.api.ModelResource;
import com.diagramador.domain.VcsCommitKind;
import com.diagramador.domain.VcsCommitOrigin;
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

    public record CommitDetail(UUID commitId, String branchName, VcsCommitKind kind, VcsCommitOrigin origin,
            String message, UUID authorUserId, String authorDisplayName, UUID parentCommitId,
            UUID secondParentCommitId, String stateHash, OffsetDateTime createdAt, List<Change> changes) {
        public CommitDetail {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
    }

    public record Checkout(String branch, ModelResource model) {
    }
}
