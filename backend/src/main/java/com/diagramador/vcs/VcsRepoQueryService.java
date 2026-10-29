package com.diagramador.vcs;

import com.diagramador.api.ApiException;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.VcsBranch;
import com.diagramador.domain.VcsCommit;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.VcsBranchRepository;
import com.diagramador.persistence.VcsCommitRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only repository queries authorized as the currently authenticated project member. */
@Service
public class VcsRepoQueryService {

    private static final Comparator<VcsCommit> NEWEST_FIRST = Comparator
            .comparing(VcsCommit::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(VcsCommit::getId, Comparator.reverseOrder());

    private final VcsAccessService accessService;
    private final VcsBranchService branchService;
    private final VcsBranchRepository branchRepository;
    private final VcsCommitRepository commitRepository;
    private final ModelSnapshotService snapshotService;
    private final UmlModelRepository modelRepository;

    public VcsRepoQueryService(VcsAccessService accessService, VcsBranchService branchService,
            VcsBranchRepository branchRepository, VcsCommitRepository commitRepository,
            ModelSnapshotService snapshotService, UmlModelRepository modelRepository) {
        this.accessService = accessService;
        this.branchService = branchService;
        this.branchRepository = branchRepository;
        this.commitRepository = commitRepository;
        this.snapshotService = snapshotService;
        this.modelRepository = modelRepository;
    }

    @Transactional(readOnly = true)
    public List<VcsResources.Commit> history(UmlModel model, String branchName, UUID authorUserId, Integer limit) {
        UmlModel managedModel = readableModel(model);
        Optional<VcsBranch> branch = resolveBranch(managedModel, branchName);
        if (branch.isEmpty()) {
            return List.of();
        }
        int boundedLimit = limit == null ? 50 : Math.max(0, Math.min(limit, 200));
        if (boundedLimit == 0) {
            return List.of();
        }

        List<VcsCommit> commits = reachableCommits(branch.get().getHeadCommit());
        return commits.stream()
                .filter(commit -> authorUserId == null || (commit.getAuthor() != null
                        && authorUserId.equals(commit.getAuthor().getId())))
                .limit(boundedLimit)
                .map(VcsCommitService::commitResource)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<VcsResources.Change> diff(UmlModel model, UUID fromCommitId, UUID toCommitId) {
        UmlModel managedModel = readableModel(model);
        if (toCommitId == null) {
            throw invalidPayload("toCommitId is required.");
        }
        VcsCommit toCommit = findCommit(managedModel, toCommitId);
        VcsCommit fromCommit = fromCommitId == null
                ? toCommit.getParent()
                : findCommit(managedModel, fromCommitId);
        SnapshotModel before = fromCommit == null ? null
                : snapshotService.fromJson(fromCommit.getSnapshot().getPayload());
        SnapshotModel after = snapshotService.fromJson(toCommit.getSnapshot().getPayload());
        return VcsCommitService.changesBetween(before, after);
    }

    /**
     * Field-level comparison of two commits, GitHub style: what {@code toCommit} changed relative to
     * {@code fromCommit} (its own parent when no start is given), the commits {@code toCommit} has that
     * {@code fromCommit} lacks, and the people behind them.
     */
    @Transactional(readOnly = true)
    public VcsResources.CommitComparison compare(UmlModel model, UUID fromCommitId, UUID toCommitId) {
        UmlModel managedModel = readableModel(model);
        if (toCommitId == null) {
            throw invalidPayload("toCommitId is required.");
        }
        VcsCommit toCommit = findCommit(managedModel, toCommitId);
        VcsCommit fromCommit = fromCommitId == null
                ? toCommit.getParent()
                : findCommit(managedModel, fromCommitId);
        SnapshotModel before = fromCommit == null ? null
                : snapshotService.fromJson(fromCommit.getSnapshot().getPayload());
        SnapshotModel after = snapshotService.fromJson(toCommit.getSnapshot().getPayload());

        Set<UUID> alreadyThere = new HashSet<>();
        VcsCommitService.reachable(fromCommit).forEach(reached -> alreadyThere.add(reached.getId()));
        List<VcsCommit> between = VcsCommitService.reachable(toCommit).stream()
                .filter(reached -> !alreadyThere.contains(reached.getId()))
                .toList();
        return new VcsResources.CommitComparison(SnapshotDiff.between(before, after),
                between.stream().map(VcsCommitService::commitResource).toList(),
                VcsCommitService.contributorsOf(between));
    }

    @Transactional(readOnly = true)
    public List<VcsResources.Commit> blame(UmlModel model, String targetType, UUID targetId, String branchName) {
        UmlModel managedModel = readableModel(model);
        TargetType parsedType = parseTargetType(targetType);
        if (targetId == null) {
            throw notFound("Element was not found.");
        }
        Optional<VcsBranch> branch = resolveBranch(managedModel, branchName);
        if (branch.isEmpty()) {
            throw notFound("Element was not found.");
        }

        List<VcsCommit> commits = reachableCommits(branch.get().getHeadCommit());
        Map<UUID, SnapshotModel> snapshots = new java.util.HashMap<>();
        Set<ChangeOwner> owners = new LinkedHashSet<>();
        for (VcsCommit commit : commits) {
            SnapshotModel snapshot = snapshot(commit, snapshots);
            findChangeOwners(snapshot, parsedType, targetId).ifPresent(owners::add);
        }
        if (owners.isEmpty()) {
            throw notFound("Element was not found.");
        }

        List<VcsResources.Commit> blamed = new ArrayList<>();
        for (VcsCommit commit : commits) {
            SnapshotModel before = commit.getParent() == null ? null : snapshot(commit.getParent(), snapshots);
            SnapshotModel after = snapshot(commit, snapshots);
            boolean changed = VcsCommitService.changesBetween(before, after).stream()
                    .map(change -> new ChangeOwner(change.targetType(), change.targetId()))
                    .anyMatch(owners::contains);
            if (changed) {
                blamed.add(VcsCommitService.commitResource(commit));
            }
        }
        return List.copyOf(blamed);
    }

    private UmlModel readableModel(UmlModel model) {
        if (model == null || model.getId() == null) {
            throw notFound("No se encontró el modelo.");
        }
        UmlModel managedModel = modelRepository.findById(model.getId())
                .orElseThrow(() -> notFound("No se encontró el modelo."));
        accessService.requireRead(managedModel.getProject().getId());
        return managedModel;
    }

    private Optional<VcsBranch> resolveBranch(UmlModel model, String branchName) {
        if (branchName == null || branchName.isBlank()) {
            if (branchRepository.findByModel_IdOrderByNameAsc(model.getId()).isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(branchService.activeBranch(model));
        }
        String normalized = branchName.trim().toLowerCase(Locale.ROOT);
        return Optional.of(branchRepository.findByModel_IdAndName(model.getId(), normalized)
                .orElseThrow(() -> notFound("Branch was not found.")));
    }

    private VcsCommit findCommit(UmlModel model, UUID commitId) {
        return commitRepository.findByModel_IdAndId(model.getId(), commitId)
                .orElseThrow(() -> notFound("Commit was not found."));
    }

    private List<VcsCommit> reachableCommits(VcsCommit head) {
        Set<UUID> seen = new HashSet<>();
        List<VcsCommit> commits = new ArrayList<>();
        collectHistory(head, seen, commits);
        commits.sort(NEWEST_FIRST);
        return commits;
    }

    private static void collectHistory(VcsCommit commit, Set<UUID> seen, List<VcsCommit> commits) {
        if (commit == null || !seen.add(commit.getId())) {
            return;
        }
        commits.add(commit);
        collectHistory(commit.getParent(), seen, commits);
        collectHistory(commit.getSecondParent(), seen, commits);
    }

    private SnapshotModel snapshot(VcsCommit commit, Map<UUID, SnapshotModel> snapshots) {
        return snapshots.computeIfAbsent(commit.getId(), ignored ->
                snapshotService.fromJson(commit.getSnapshot().getPayload()));
    }

    private static Optional<ChangeOwner> findChangeOwners(SnapshotModel snapshot, TargetType targetType,
            UUID targetId) {
        for (var classifier : snapshot.classifiers()) {
            switch (targetType) {
                case CLASSIFIER -> {
                    if (targetId.equals(classifier.id())) {
                        return Optional.of(new ChangeOwner("CLASSIFIER", classifier.id()));
                    }
                }
                case ATTRIBUTE -> {
                    if (classifier.attributes().stream().anyMatch(attribute -> targetId.equals(attribute.id()))) {
                        return Optional.of(new ChangeOwner("CLASSIFIER", classifier.id()));
                    }
                }
                case OPERATION -> {
                    if (classifier.operations().stream().anyMatch(operation -> targetId.equals(operation.id()))) {
                        return Optional.of(new ChangeOwner("CLASSIFIER", classifier.id()));
                    }
                }
                case PARAMETER -> {
                    if (classifier.operations().stream().flatMap(operation -> operation.parameters().stream())
                            .anyMatch(parameter -> targetId.equals(parameter.id()))) {
                        return Optional.of(new ChangeOwner("CLASSIFIER", classifier.id()));
                    }
                }
                case CONSTRAINT -> {
                    if (classifier.constraints().stream().anyMatch(constraint -> targetId.equals(constraint.id()))) {
                        return Optional.of(new ChangeOwner("CLASSIFIER", classifier.id()));
                    }
                }
                default -> {
                    // Other target types are represented by relationships or views below.
                }
            }
        }
        for (var relationship : snapshot.relationships()) {
            if (targetType == TargetType.RELATIONSHIP && targetId.equals(relationship.id())) {
                return Optional.of(new ChangeOwner("RELATIONSHIP", relationship.id()));
            }
            if (targetType == TargetType.RELATIONSHIP_END
                    && relationship.ends().stream().anyMatch(end -> targetId.equals(end.id()))) {
                return Optional.of(new ChangeOwner("RELATIONSHIP", relationship.id()));
            }
            if (targetType == TargetType.CONSTRAINT
                    && relationship.constraints().stream().anyMatch(constraint -> targetId.equals(constraint.id()))) {
                return Optional.of(new ChangeOwner("RELATIONSHIP", relationship.id()));
            }
        }
        if (targetType == TargetType.DIAGRAM_VIEW) {
            return snapshot.views().stream()
                    .filter(view -> targetId.equals(view.id()))
                    .map(view -> new ChangeOwner("VIEW", view.id()))
                    .findFirst();
        }
        return Optional.empty();
    }

    private static TargetType parseTargetType(String targetType) {
        try {
            return TargetType.valueOf(targetType == null ? "" : targetType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw notFound("Element was not found.");
        }
    }

    private static ApiException invalidPayload(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", message, List.of(), null);
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, List.of(), null);
    }

    private record ChangeOwner(String targetType, UUID targetId) {
        private ChangeOwner {
            Objects.requireNonNull(targetType);
            Objects.requireNonNull(targetId);
        }
    }
}
