package com.diagramador.vcs;

import com.diagramador.api.ApiException;
import com.diagramador.command.ModelStateHasher;
import com.diagramador.command.ValidationState;
import com.diagramador.domain.ModelVersion;
import com.diagramador.domain.Project;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.domain.VcsBranch;
import com.diagramador.domain.VcsCommit;
import com.diagramador.domain.VcsCommitKind;
import com.diagramador.domain.VcsCommitOrigin;
import com.diagramador.domain.VcsMergeRequest;
import com.diagramador.domain.VcsMergeState;
import com.diagramador.domain.VcsSnapshot;
import com.diagramador.persistence.ModelVersionRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UserAccountRepository;
import com.diagramador.persistence.VcsBranchRepository;
import com.diagramador.persistence.VcsCommitRepository;
import com.diagramador.persistence.VcsMergeRequestRepository;
import com.diagramador.persistence.VcsSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opening, reviewing, and merging frozen pull requests between branches of one diagram. */
@Service
public class VcsMergeRequestService {

    private final VcsAccessService accessService;
    private final VcsBranchService branchService;
    private final ModelStateHasher stateHasher;
    private final ModelSnapshotService snapshotService;
    private final VcsBranchRepository branchRepository;
    private final VcsCommitRepository commitRepository;
    private final VcsSnapshotRepository snapshotRepository;
    private final VcsMergeRequestRepository mergeRequestRepository;
    private final UmlModelRepository modelRepository;
    private final ModelVersionRepository versionRepository;
    private final ProjectRepository projectRepository;
    private final UserAccountRepository userRepository;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    public VcsMergeRequestService(VcsAccessService accessService, VcsBranchService branchService,
            ModelStateHasher stateHasher, ModelSnapshotService snapshotService, VcsBranchRepository branchRepository,
            VcsCommitRepository commitRepository, VcsSnapshotRepository snapshotRepository,
            VcsMergeRequestRepository mergeRequestRepository, UmlModelRepository modelRepository,
            ModelVersionRepository versionRepository, ProjectRepository projectRepository,
            UserAccountRepository userRepository, ObjectMapper objectMapper) {
        this.accessService = accessService;
        this.branchService = branchService;
        this.stateHasher = stateHasher;
        this.snapshotService = snapshotService;
        this.branchRepository = branchRepository;
        this.commitRepository = commitRepository;
        this.snapshotRepository = snapshotRepository;
        this.mergeRequestRepository = mergeRequestRepository;
        this.modelRepository = modelRepository;
        this.versionRepository = versionRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<VcsResources.MergeRequest> list(UmlModel model) {
        UmlModel managedModel = requireModel(model.getId());
        accessService.requireRead(managedModel.getProject().getId());
        return mergeRequestRepository.findByModel_IdOrderByCreatedAtDescIdDesc(managedModel.getId()).stream()
                .map(this::resource)
                .toList();
    }

    @Transactional(readOnly = true)
    public VcsResources.MergeRequest detail(UmlModel model, UUID mergeRequestId) {
        UmlModel managedModel = requireModel(model.getId());
        accessService.requireRead(managedModel.getProject().getId());
        return resource(requireMergeRequest(managedModel.getId(), mergeRequestId));
    }

    @Transactional
    public VcsResources.MergeRequest open(UmlModel model, String sourceName, String targetName, String title,
            UserAccount author) {
        UmlModel managedModel = requireModel(model.getId());
        // Opening a merge request is an editor/branch operation, not a direct commit to the active branch.
        accessService.requireBranch(managedModel.getProject().getId());
        String normalizedSource = normalizeBranchName(sourceName);
        String normalizedTarget = normalizeBranchName(targetName);
        if (normalizedSource.isEmpty() || normalizedTarget.isEmpty()) {
            throw invalidPayload("sourceBranch and targetBranch are required.");
        }
        if (normalizedSource.equals(normalizedTarget)) {
            throw invalidPayload("Source and target branches must be different.");
        }
        String normalizedTitle = normalizeTitle(title);
        branchService.ensureMain(managedModel, author);
        VcsBranch sourceBranch = findBranch(managedModel.getId(), normalizedSource, "Source");
        VcsBranch targetBranch = findBranch(managedModel.getId(), normalizedTarget, "Target");
        if (sourceBranch.getId().equals(targetBranch.getId())) {
            throw invalidPayload("Source and target branches must be different.");
        }
        if (mergeRequestRepository.existsBySourceBranch_IdAndTargetBranch_IdAndState(
                sourceBranch.getId(), targetBranch.getId(), VcsMergeState.OPEN)) {
            throw new ApiException(HttpStatus.CONFLICT, "MERGE_REQUEST_EXISTS",
                    "An open merge request already exists for this ordered branch pair.", List.of(), null);
        }

        VcsCommit sourceHead = sourceBranch.getHeadCommit();
        VcsCommit targetHead = targetBranch.getHeadCommit();
        VcsCommit mergeBase = MergeDiff.findMergeBase(sourceHead, targetHead)
                .orElseThrow(() -> invalidPayload("No merge base exists for branches '" + sourceBranch.getName()
                        + "' and '" + targetBranch.getName() + "'."));
        MergeDiff.Result comparison = MergeDiff.merge(snapshot(mergeBase), snapshot(sourceHead), snapshot(targetHead));
        JsonNode conflicts = objectMapper.valueToTree(comparison.conflicts());
        VcsMergeRequest request = VcsMergeRequest.create(managedModel, sourceBranch, targetBranch,
                sourceHead, targetHead, mergeBase, normalizedTitle, comparison.hasConflicts(), conflicts, author);
        VcsMergeRequest saved = mergeRequestRepository.saveAndFlush(request);
        entityManager.refresh(saved);
        return resource(saved);
    }

    @Transactional
    public VcsResources.MergeRequest merge(UmlModel model, UUID mergeRequestId, UserAccount author) {
        UmlModel managedModel = requireModel(model.getId());
        accessService.requireMerge(managedModel.getProject().getId());
        VcsMergeRequest request = requireMergeRequest(managedModel.getId(), mergeRequestId);
        requireOpen(request);
        if (request.hasConflicts()) {
            throw new ApiException(HttpStatus.CONFLICT, "MERGE_CONFLICTS",
                    "This merge request has conflicts and cannot be merged.", List.of(), null);
        }

        VcsBranch targetBranch = request.getTargetBranch();
        VcsCommit sourceHead = request.getSourceCommit();
        // The merge must build on the target's CURRENT head. The head recorded when the request was opened can be
        // stale (another merge or a commit moved the target since); merging on it would drop those commits.
        VcsCommit targetHead = targetBranch.getHeadCommit();
        VcsCommit mergeBase = request.getMergeBaseCommit();
        boolean targetMoved = !targetHead.getId().equals(request.getTargetCommit().getId());
        if (targetMoved) {
            mergeBase = MergeDiff.findMergeBase(sourceHead, targetHead)
                    .orElseThrow(() -> invalidPayload("No merge base exists for branches '"
                            + request.getSourceBranch().getName() + "' and '" + targetBranch.getName() + "'."));
        }
        MergeDiff.Result comparison = MergeDiff.merge(snapshot(mergeBase), snapshot(sourceHead), snapshot(targetHead));
        if (comparison.hasConflicts()) {
            // Fail closed rather than merge a comparison the reviewer never saw.
            throw new ApiException(HttpStatus.CONFLICT, "MERGE_CONFLICTS", targetMoved
                    ? "The target branch changed after this merge request was opened and it now has conflicts."
                    : "The frozen merge request no longer has a conflict-free comparison.", List.of(), null);
        }

        SnapshotModel mergedSnapshot = comparison.mergedSnapshot();
        String stateHash = stateHasher.hash(mergedSnapshot);
        VcsSnapshot mergedState = snapshotRepository.findByModel_IdAndStateHash(managedModel.getId(), stateHash)
                .orElseGet(() -> snapshotRepository.saveAndFlush(
                        VcsSnapshot.create(managedModel, stateHash, snapshotService.toJson(mergedSnapshot))));
        VcsCommit mergeCommit = commitRepository.saveAndFlush(VcsCommit.create(managedModel, targetHead, sourceHead,
                mergedState, targetBranch.getName(), VcsCommitKind.MERGE, VcsCommitOrigin.MERGE,
                "Merge " + request.getSourceBranch().getName() + " into " + targetBranch.getName(), author));
        targetBranch.moveHeadTo(mergeCommit);
        branchRepository.saveAndFlush(targetBranch);
        request.markMerged(mergeCommit, author);
        mergeRequestRepository.saveAndFlush(request);

        boolean targetIsActive = branchService.activeBranch(managedModel).getId().equals(targetBranch.getId());
        UUID modelId = managedModel.getId();
        UUID projectId = managedModel.getProject().getId();
        if (targetIsActive) {
            long expectedRevision = managedModel.getCurrentRevision();
            snapshotService.materialize(managedModel, mergedSnapshot);

            // The materializer flushes and clears. Re-read every entity used after this point.
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> notFound("Project was not found."));
            UmlModel refreshedModel = requireModel(modelId);
            long nextRevision = expectedRevision + 1;
            if (modelRepository.bumpRevisionIfCurrent(modelId, expectedRevision, nextRevision) != 1) {
                throw new IllegalStateException("Model revision changed during merge materialization");
            }
            refreshedModel.setCurrentRevision(nextRevision);
            UserAccount refreshedAuthor = author == null ? null
                    : userRepository.findById(author.getId()).orElse(null);
            versionRepository.saveAndFlush(ModelVersion.create(refreshedModel, nextRevision, expectedRevision,
                    stateHash, ValidationState.VALID, refreshedAuthor));
            entityManager.flush();
            entityManager.clear();
            projectRepository.findById(project.getId()).orElseThrow(() -> notFound("Project was not found."));
            requireModel(modelId);
        }
        return resource(requireMergeRequest(modelId, mergeRequestId));
    }

    @Transactional
    public VcsResources.MergeRequest close(UmlModel model, UUID mergeRequestId, UserAccount author) {
        UmlModel managedModel = requireModel(model.getId());
        accessService.requireMerge(managedModel.getProject().getId());
        VcsMergeRequest request = requireMergeRequest(managedModel.getId(), mergeRequestId);
        requireOpen(request);
        request.markClosed(author);
        return resource(mergeRequestRepository.saveAndFlush(request));
    }

    private SnapshotModel snapshot(VcsCommit commit) {
        if (commit == null || commit.getSnapshot() == null) {
            throw invalidPayload("The merge request is missing a required commit snapshot.");
        }
        return snapshotService.fromJson(commit.getSnapshot().getPayload());
    }

    private VcsBranch findBranch(UUID modelId, String name, String side) {
        return branchRepository.findByModel_IdAndName(modelId, name)
                .orElseThrow(() -> notFound(side + " branch was not found."));
    }

    private VcsMergeRequest requireMergeRequest(UUID modelId, UUID mergeRequestId) {
        return mergeRequestRepository.findByModel_IdAndId(modelId, mergeRequestId)
                .orElseThrow(() -> notFound("Merge request was not found."));
    }

    private UmlModel requireModel(UUID modelId) {
        return modelRepository.findById(modelId).orElseThrow(() -> notFound("Model was not found."));
    }

    private void requireOpen(VcsMergeRequest request) {
        if (request.getState() != VcsMergeState.OPEN) {
            throw invalidPayload("Only an OPEN merge request can be merged or closed.");
        }
    }

    private VcsResources.MergeRequest resource(VcsMergeRequest request) {
        UserAccount openedBy = request.getOpenedByUser();
        UserAccount decidedBy = request.getDecidedByUser();
        JsonNode conflicts = request.getConflicts();
        if (conflicts == null || conflicts.isNull()) {
            conflicts = objectMapper.createArrayNode();
        }
        return new VcsResources.MergeRequest(request.getId(), request.getTitle(), request.getState(),
                request.getSourceBranch().getName(), request.getTargetBranch().getName(),
                request.getSourceCommit().getId(), request.getTargetCommit().getId(),
                request.getMergeBaseCommit() == null ? null : request.getMergeBaseCommit().getId(),
                request.hasConflicts(), conflicts,
                openedBy == null ? null : openedBy.getDisplayName(),
                decidedBy == null ? null : decidedBy.getDisplayName(), request.getCreatedAt());
    }

    private static String normalizeBranchName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeTitle(String title) {
        if (title == null) {
            throw invalidPayload("title is required.");
        }
        String normalized = title.trim();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw invalidPayload("title must contain 1 to 200 characters.");
        }
        return normalized;
    }

    private static ApiException invalidPayload(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", message, List.of(), null);
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, List.of(), null);
    }
}
