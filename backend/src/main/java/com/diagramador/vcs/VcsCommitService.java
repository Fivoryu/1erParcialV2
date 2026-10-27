package com.diagramador.vcs;

import com.diagramador.api.ApiException;
import com.diagramador.command.ModelStateHasher;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.domain.VcsBranch;
import com.diagramador.domain.VcsCommit;
import com.diagramador.domain.VcsCommitKind;
import com.diagramador.domain.VcsCommitOrigin;
import com.diagramador.domain.VcsSnapshot;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.VcsBranchRepository;
import com.diagramador.persistence.VcsCommitRepository;
import com.diagramador.persistence.VcsSnapshotRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit commits, working-copy status, branch history, and snapshot-based commit diffs. */
@Service
public class VcsCommitService {

    private final VcsAccessService accessService;
    private final VcsBranchService branchService;
    private final ModelStateHasher stateHasher;
    private final ModelSnapshotService snapshotService;
    private final UmlModelRepository modelRepository;
    private final VcsBranchRepository branchRepository;
    private final VcsCommitRepository commitRepository;
    private final VcsSnapshotRepository snapshotRepository;

    public VcsCommitService(VcsAccessService accessService, VcsBranchService branchService,
            ModelStateHasher stateHasher, ModelSnapshotService snapshotService, UmlModelRepository modelRepository,
            VcsBranchRepository branchRepository, VcsCommitRepository commitRepository,
            VcsSnapshotRepository snapshotRepository) {
        this.accessService = accessService;
        this.branchService = branchService;
        this.stateHasher = stateHasher;
        this.snapshotService = snapshotService;
        this.modelRepository = modelRepository;
        this.branchRepository = branchRepository;
        this.commitRepository = commitRepository;
        this.snapshotRepository = snapshotRepository;
    }

    @Transactional(readOnly = true)
    public VcsResources.Status status(UmlModel model) {
        UmlModel managedModel = requireModel(model.getId());
        VcsBranch active = branchService.activeBranch(managedModel);
        String currentHash = stateHasher.hash(managedModel);
        String headHash = active.getHeadCommit().getSnapshot().getStateHash();
        return new VcsResources.Status(active.getName(), active.getHeadCommit().getId(),
                !Objects.equals(currentHash, headHash));
    }

    @Transactional
    public VcsResources.Commit commit(UmlModel model, String message, UserAccount author) {
        return commit(model, message, author, VcsCommitOrigin.MANUAL);
    }

    @Transactional
    public VcsResources.Commit commit(UmlModel model, String message, UserAccount author, VcsCommitOrigin origin) {
        UmlModel managedModel = requireModel(model.getId());
        UUID projectId = managedModel.getProject().getId();
        accessService.requireCommit(projectId);
        String normalizedMessage = normalizeMessage(message);
        VcsCommitOrigin commitOrigin = origin == null ? VcsCommitOrigin.MANUAL : origin;
        if (commitOrigin != VcsCommitOrigin.MANUAL && commitOrigin != VcsCommitOrigin.AI
                && commitOrigin != VcsCommitOrigin.IMPORT) {
            throw invalidPayload("Commit origin must be MANUAL, AI, or IMPORT.");
        }

        branchService.ensureMain(managedModel, author);
        VcsBranch branch = branchService.activeBranch(managedModel);
        String stateHash = stateHasher.hash(managedModel);
        VcsCommit parent = branch.getHeadCommit();
        if (Objects.equals(stateHash, parent.getSnapshot().getStateHash())) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKING_COPY_CLEAN",
                    "There are no working-copy changes to commit.", List.of(), null);
        }

        SnapshotModel captured = snapshotService.capture(managedModel);
        VcsSnapshot snapshot = snapshotRepository.findByModel_IdAndStateHash(managedModel.getId(), stateHash)
                .orElseGet(() -> snapshotRepository.saveAndFlush(
                        VcsSnapshot.create(managedModel, stateHash, snapshotService.toJson(captured))));
        VcsCommit commit = commitRepository.saveAndFlush(VcsCommit.create(managedModel, parent, null, snapshot,
                branch.getName(), VcsCommitKind.MANUAL, commitOrigin, normalizedMessage, author));
        branch.moveHeadTo(commit);
        branchRepository.saveAndFlush(branch);
        return commitResource(commit);
    }

    @Transactional(readOnly = true)
    public List<VcsResources.Commit> history(UmlModel model, String branchName, Integer requestedLimit) {
        UmlModel managedModel = requireModel(model.getId());
        VcsBranch branch = branchName == null || branchName.isBlank()
                ? branchService.activeBranch(managedModel)
                : findBranch(managedModel.getId(), branchName);
        int limit = requestedLimit == null ? 50 : Math.max(0, Math.min(requestedLimit, 200));
        if (limit == 0) {
            return List.of();
        }

        Set<UUID> seen = new HashSet<>();
        List<VcsCommit> reachable = new ArrayList<>();
        collectHistory(branch.getHeadCommit(), seen, reachable);
        reachable.sort(Comparator.comparing(VcsCommit::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(VcsCommit::getId, Comparator.reverseOrder()));
        return reachable.stream().limit(limit).map(VcsCommitService::commitResource).toList();
    }

    @Transactional(readOnly = true)
    public VcsResources.CommitDetail detail(UmlModel model, UUID commitId) {
        UmlModel managedModel = requireModel(model.getId());
        VcsCommit commit = commitRepository.findByModel_IdAndId(managedModel.getId(), commitId)
                .orElseThrow(() -> notFound("Commit was not found."));
        SnapshotModel child = snapshotService.fromJson(commit.getSnapshot().getPayload());
        SnapshotModel parent = commit.getParent() == null ? null
                : snapshotService.fromJson(commit.getParent().getSnapshot().getPayload());
        List<VcsResources.Change> changes = changesBetween(parent, child);

        UserAccount author = commit.getAuthor();
        return new VcsResources.CommitDetail(commit.getId(), commit.getBranchName(), commit.getKind(),
                commit.getOrigin(), commit.getMessage(), author == null ? null : author.getId(),
                author == null ? null : author.getDisplayName(),
                commit.getParent() == null ? null : commit.getParent().getId(),
                commit.getSecondParent() == null ? null : commit.getSecondParent().getId(),
                commit.getSnapshot().getStateHash(), commit.getCreatedAt(), changes,
                SnapshotDiff.between(parent, child));
    }

    private void collectHistory(VcsCommit commit, Set<UUID> seen, List<VcsCommit> reachable) {
        if (commit == null || !seen.add(commit.getId())) {
            return;
        }
        reachable.add(commit);
        collectHistory(commit.getParent(), seen, reachable);
        collectHistory(commit.getSecondParent(), seen, reachable);
    }

    private VcsBranch findBranch(UUID modelId, String branchName) {
        String normalized = branchName.trim().toLowerCase(Locale.ROOT);
        return branchRepository.findByModel_IdAndName(modelId, normalized)
                .orElseThrow(() -> notFound("Branch was not found."));
    }

    private UmlModel requireModel(UUID modelId) {
        return modelRepository.findById(modelId).orElseThrow(() -> notFound("Model was not found."));
    }

    private static String normalizeMessage(String message) {
        if (message == null) {
            throw invalidPayload("Commit message is required.");
        }
        String normalized = message.trim();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw invalidPayload("Commit message must contain 1 to 500 characters.");
        }
        return normalized;
    }

    static VcsResources.Commit commitResource(VcsCommit commit) {
        UserAccount author = commit.getAuthor();
        return new VcsResources.Commit(commit.getId(), commit.getBranchName(), commit.getKind(), commit.getOrigin(),
                commit.getMessage(), author == null ? null : author.getId(),
                author == null ? null : author.getDisplayName(),
                commit.getParent() == null ? null : commit.getParent().getId(),
                commit.getSecondParent() == null ? null : commit.getSecondParent().getId(),
                commit.getSnapshot().getStateHash(), commit.getCreatedAt());
    }

    static List<VcsResources.Change> changesBetween(SnapshotModel parent, SnapshotModel child) {
        Objects.requireNonNull(child, "child");
        List<VcsResources.Change> changes = new ArrayList<>();
        changes.addAll(diff(parent == null ? List.of() : parent.classifiers(), child.classifiers(),
                com.diagramador.api.ModelResource.ClassifierResource::id,
                com.diagramador.api.ModelResource.ClassifierResource::name, "CLASSIFIER"));
        changes.addAll(diff(parent == null ? List.of() : parent.relationships(), child.relationships(),
                com.diagramador.api.ModelResource.RelationshipResource::id,
                com.diagramador.api.ModelResource.RelationshipResource::name, "RELATIONSHIP"));
        changes.addAll(diff(parent == null ? List.of() : parent.views(), child.views(),
                SnapshotModel.SnapshotView::id, SnapshotModel.SnapshotView::name, "VIEW"));
        return List.copyOf(changes);
    }

    private static <T> List<VcsResources.Change> diff(List<T> before, List<T> after,
            Function<T, UUID> id, Function<T, String> name, String targetType) {
        Map<UUID, T> beforeById = index(before, id);
        Map<UUID, T> afterById = index(after, id);
        Set<UUID> allIds = new TreeSet<>();
        allIds.addAll(beforeById.keySet());
        allIds.addAll(afterById.keySet());
        List<VcsResources.Change> changes = new ArrayList<>();
        for (UUID targetId : allIds) {
            T oldValue = beforeById.get(targetId);
            T newValue = afterById.get(targetId);
            if (oldValue == null) {
                changes.add(new VcsResources.Change("ADDED", targetType, targetId, name.apply(newValue)));
            } else if (newValue == null) {
                changes.add(new VcsResources.Change("REMOVED", targetType, targetId, name.apply(oldValue)));
            } else if (!Objects.equals(oldValue, newValue)) {
                changes.add(new VcsResources.Change("MODIFIED", targetType, targetId, name.apply(newValue)));
            }
        }
        return changes;
    }

    private static <T> Map<UUID, T> index(List<T> values, Function<T, UUID> id) {
        Map<UUID, T> indexed = new HashMap<>();
        values.forEach(value -> indexed.put(id.apply(value), value));
        return indexed;
    }

    private static ApiException invalidPayload(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", message, List.of(), null);
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, List.of(), null);
    }
}
