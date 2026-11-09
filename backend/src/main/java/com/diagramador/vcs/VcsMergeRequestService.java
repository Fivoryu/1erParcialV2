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
import com.diagramador.domain.VcsMergeRequestApproval;
import com.diagramador.domain.VcsMergeRequestReview;
import com.diagramador.domain.VcsMergeState;
import com.diagramador.domain.VcsSnapshot;
import com.diagramador.persistence.ModelVersionRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UserAccountRepository;
import com.diagramador.persistence.VcsBranchRepository;
import com.diagramador.persistence.VcsCommitRepository;
import com.diagramador.persistence.VcsMergeRequestApprovalRepository;
import com.diagramador.persistence.VcsMergeRequestRepository;
import com.diagramador.persistence.VcsMergeRequestReviewerRepository;
import com.diagramador.persistence.VcsMergeRequestReviewRepository;
import com.diagramador.persistence.VcsSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private final VcsMergeRequestApprovalRepository approvalRepository;
    private final VcsMergeRequestReviewerRepository reviewerRepository;
    private final VcsMergeRequestReviewRepository reviewRepository;
    private final VcsPermissionService permissionService;
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
            VcsMergeRequestRepository mergeRequestRepository,
            VcsMergeRequestApprovalRepository approvalRepository, VcsMergeRequestReviewerRepository reviewerRepository,
            VcsMergeRequestReviewRepository reviewRepository, VcsPermissionService permissionService, UmlModelRepository modelRepository,
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
        this.approvalRepository = approvalRepository;
        this.reviewerRepository = reviewerRepository;
        this.reviewRepository = reviewRepository;
        this.permissionService = permissionService;
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

    /**
     * What the source branch adds on top of the merge base (what a pull request shows): the field-level changes, the
     * commits it brings and the people behind them. An open request is compared against the target as it is now, so
     * the diff matches what a merge would apply; a merged or closed one keeps the base it was decided with.
     */
    @Transactional(readOnly = true)
    public VcsResources.MergeRequestDiff diff(UmlModel model, UUID mergeRequestId) {
        UmlModel managedModel = requireModel(model.getId());
        accessService.requireRead(managedModel.getProject().getId());
        VcsMergeRequest request = requireMergeRequest(managedModel.getId(), mergeRequestId);
        VcsCommit sourceHead = request.getSourceCommit();
        VcsCommit base = request.getMergeBaseCommit();
        boolean hasConflicts = request.hasConflicts();
        JsonNode conflicts = request.getConflicts() == null ? objectMapper.createArrayNode() : request.getConflicts();
        if (request.getState() == VcsMergeState.OPEN) {
            VcsCommit targetHead = request.getTargetBranch().getHeadCommit();
            base = MergeDiff.findMergeBase(sourceHead, targetHead).orElse(base);
            if (base != null) {
                // The conflicts frozen when the request was opened go stale once the target moves.
                MergeDiff.Result comparison = MergeDiff.merge(snapshot(base), snapshot(sourceHead),
                        snapshot(targetHead));
                hasConflicts = comparison.hasConflicts();
                conflicts = objectMapper.valueToTree(comparison.conflicts());
            }
        }
        List<VcsResources.ElementChange> changes = SnapshotDiff.between(base == null ? null : snapshot(base),
                snapshot(sourceHead));

        Set<UUID> alreadyInBase = new HashSet<>();
        VcsCommitService.reachable(base).forEach(reached -> alreadyInBase.add(reached.getId()));
        List<VcsCommit> broughtByTheRequest = VcsCommitService.reachable(sourceHead).stream()
                .filter(reached -> !alreadyInBase.contains(reached.getId()))
                .toList();
        return new VcsResources.MergeRequestDiff(changes,
                broughtByTheRequest.stream().map(VcsCommitService::commitResource).toList(),
                VcsCommitService.contributorsOf(broughtByTheRequest), hasConflicts, conflicts);
    }

    @Transactional
    public VcsResources.MergeRequest open(UmlModel model, String sourceName, String targetName, String title,
            List<UUID> reviewerIds, UserAccount author) {
        UmlModel managedModel = requireModel(model.getId());
        // Opening a merge request is an editor/branch operation, not a direct commit to the active branch.
        accessService.requireBranch(managedModel.getProject().getId());
        List<UUID> selected = reviewerIds == null ? List.of() : reviewerIds;
        Set<UUID> members = permissionService.list(managedModel, author).stream()
                .map(VcsResources.Permission::userId).collect(java.util.stream.Collectors.toSet());
        if (selected.stream().anyMatch(id -> id == null || !members.contains(id))) {
            throw invalidPayload("Each reviewer must be an active project member.");
        }
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
        selected.stream().distinct().forEach(id -> reviewerRepository.insertIfAbsent(saved.getId(), id));
        return resource(saved);
    }

    @Transactional
    public VcsResources.MergeRequest merge(UmlModel model, UUID mergeRequestId, UserAccount author) {
        UmlModel managedModel = requireModel(model.getId());
        accessService.requireMerge(managedModel.getProject().getId());
        VcsMergeRequest request = requireMergeRequestForUpdate(managedModel.getId(), mergeRequestId);
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
        // Conflicts are reported first: the opener has to resolve them before an approval matters.
        if (approvalRequired(request, approvalRepository.findByMergeRequest_IdOrderByApprovedAtAscUser_IdAsc(
                request.getId()))) {
            throw new ApiException(HttpStatus.CONFLICT, "APPROVAL_REQUIRED",
                    "Another editor or owner must approve this merge request before it can be merged.",
                    List.of(), null);
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
                    .orElseThrow(() -> notFound("No se encontró el proyecto."));
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
            projectRepository.findById(project.getId()).orElseThrow(() -> notFound("No se encontró el proyecto."));
            requireModel(modelId);
        }
        return resource(requireMergeRequest(modelId, mergeRequestId));
    }

    /** Records the caller's approval; idempotent, and never by the person who opened the request. */
    @Transactional
    public VcsResources.MergeRequest approve(UmlModel model, UUID mergeRequestId, UserAccount approver) {
        UmlModel managedModel = requireModel(model.getId());
        UUID projectId = managedModel.getProject().getId();
        if (approver == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only a project member can approve a merge request.", List.of(), null);
        }
        accessService.requireReviewer(projectId, approver.getId());
        VcsMergeRequest request = requireMergeRequest(managedModel.getId(), mergeRequestId);
        if (request.getState() != VcsMergeState.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "MERGE_REQUEST_NOT_OPEN",
                    "Only an OPEN merge request can be approved.", List.of(), null);
        }
        UserAccount opener = request.getOpenedByUser();
        if (opener != null && opener.getId().equals(approver.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "No puedes aprobar una solicitud de fusión que abriste tú.", List.of(), null);
        }
        approvalRepository.insertIfAbsent(request.getId(), approver.getId());
        return resource(request);
    }

    @Transactional
    public VcsResources.MergeRequest requestChanges(UmlModel model, UUID mergeRequestId, String message, UserAccount reviewer) {
        UmlModel managedModel = requireModel(model.getId());
        if (reviewer == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a project member can review a merge request.", List.of(), null);
        }
        accessService.requireReviewer(managedModel.getProject().getId(), reviewer.getId());
        VcsMergeRequest request = requireMergeRequestForUpdate(managedModel.getId(), mergeRequestId);
        if (request.getState() != VcsMergeState.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "MERGE_REQUEST_NOT_OPEN", "Only an OPEN merge request can be reviewed.", List.of(), null);
        }
        if (request.getOpenedByUser() != null && request.getOpenedByUser().getId().equals(reviewer.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You cannot review your own merge request.", List.of(), null);
        }
        String normalized = message == null ? "" : message.trim();
        if (normalized.isEmpty() || normalized.length() > 2000) {
            throw invalidPayload("message must contain 1 to 2000 characters.");
        }
        reviewRepository.saveAndFlush(VcsMergeRequestReview.create(request, reviewer, normalized));
        if (request.getOpenedByUser() != null) {
            entityManager.createNativeQuery("""
                    INSERT INTO diagrammer.push_outbox (project_id, merge_request_id, recipient_id, event_type)
                    VALUES (:project, :request, :recipient, 'REVIEW_CHANGES')
                    """)
                    .setParameter("project", managedModel.getProject().getId())
                    .setParameter("request", request.getId())
                    .setParameter("recipient", request.getOpenedByUser().getId())
                    .executeUpdate();
        }
        return resource(request);
    }

    @Transactional
    public VcsResources.MergeRequest close(UmlModel model, UUID mergeRequestId, UserAccount author) {
        UmlModel managedModel = requireModel(model.getId());
        accessService.requireMerge(managedModel.getProject().getId());
        VcsMergeRequest request = requireMergeRequestForUpdate(managedModel.getId(), mergeRequestId);
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

    private VcsMergeRequest requireMergeRequestForUpdate(UUID modelId, UUID mergeRequestId) {
        return mergeRequestRepository.findForUpdate(modelId, mergeRequestId)
                .orElseThrow(() -> notFound("Merge request was not found."));
    }

    private UmlModel requireModel(UUID modelId) {
        return modelRepository.findById(modelId).orElseThrow(() -> notFound("No se encontró el modelo."));
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
        List<VcsMergeRequestApproval> approvals = approvalRepository
                .findByMergeRequest_IdOrderByApprovedAtAscUser_IdAsc(request.getId());
        return new VcsResources.MergeRequest(request.getId(), request.getTitle(), request.getState(),
                request.getSourceBranch().getName(), request.getTargetBranch().getName(),
                request.getSourceCommit().getId(), request.getTargetCommit().getId(),
                request.getMergeBaseCommit() == null ? null : request.getMergeBaseCommit().getId(),
                request.hasConflicts(), conflicts,
                openedBy == null ? null : openedBy.getDisplayName(),
                decidedBy == null ? null : decidedBy.getDisplayName(), request.getCreatedAt(),
                approvals.stream()
                        .map(approval -> new VcsResources.Approval(approval.getUser().getId(),
                                approval.getUser().getDisplayName(), approval.getApprovedAt()))
                        .toList(),
                approvalRequired(request, approvals), openedBy == null ? null : openedBy.getId(),
                reviewerRepository.findByMergeRequest_IdOrderByUser_IdAsc(request.getId()).stream()
                        .map(reviewer -> new VcsResources.Reviewer(reviewer.getUser().getId(),
                                reviewer.getUser().getDisplayName()))
                        .toList(),
                reviewRepository.findByMergeRequest_IdOrderByCreatedAtAscIdAsc(request.getId()).stream()
                        .map(review -> new VcsResources.Review(review.getId(), review.getUser().getId(),
                                review.getUser().getDisplayName(), review.getMessage(), review.getCreatedAt()))
                        .toList());
    }

    /**
     * An open request still needs an approval while some other active editor or owner could give one and nobody but
     * the opener has. A project with no such member merges without approval.
     */
    private boolean approvalRequired(VcsMergeRequest request, List<VcsMergeRequestApproval> approvals) {
        if (request.getState() != VcsMergeState.OPEN) {
            return false;
        }
        UUID openerId = request.getOpenedByUser() == null ? null : request.getOpenedByUser().getId();
        // Only approvals from people who are still active editors/owners count; a demoted or removed approver's
        // approval is stale.
        Set<UUID> reviewers = accessService.activeReviewerIds(request.getModel().getProject().getId());
        if (reviewers.stream().allMatch(userId -> userId.equals(openerId))) {
            return false;
        }
        return approvals.stream().map(approval -> approval.getUser().getId())
                .noneMatch(userId -> !userId.equals(openerId) && reviewers.contains(userId));
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
