package com.diagramador.vcs;

import com.diagramador.api.ApiException;
import com.diagramador.command.ModelStateHasher;
import com.diagramador.command.ValidationState;
import com.diagramador.domain.Project;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.domain.VcsBranch;
import com.diagramador.domain.VcsCommit;
import com.diagramador.domain.VcsCommitKind;
import com.diagramador.domain.VcsCommitOrigin;
import com.diagramador.domain.VcsSnapshot;
import com.diagramador.domain.ModelVersion;
import com.diagramador.persistence.ModelVersionRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UserAccountRepository;
import com.diagramador.persistence.VcsBranchRepository;
import com.diagramador.persistence.VcsCommitRepository;
import com.diagramador.persistence.VcsSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates branches and moves the single project working copy between their snapshots. */
@Service
public class VcsBranchService {

    private final VcsAccessService accessService;
    private final ModelStateHasher stateHasher;
    private final ModelSnapshotService snapshotService;
    private final VcsBranchRepository branchRepository;
    private final VcsCommitRepository commitRepository;
    private final VcsSnapshotRepository snapshotRepository;
    private final UmlModelRepository modelRepository;
    private final ModelVersionRepository versionRepository;
    private final UserAccountRepository userRepository;
    private final ProjectRepository projectRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public VcsBranchService(VcsAccessService accessService, ModelStateHasher stateHasher,
            ModelSnapshotService snapshotService, VcsBranchRepository branchRepository, VcsCommitRepository commitRepository,
            VcsSnapshotRepository snapshotRepository, UmlModelRepository modelRepository,
            ModelVersionRepository versionRepository, UserAccountRepository userRepository,
            ProjectRepository projectRepository) {
        this.accessService = accessService;
        this.stateHasher = stateHasher;
        this.snapshotService = snapshotService;
        this.branchRepository = branchRepository;
        this.commitRepository = commitRepository;
        this.snapshotRepository = snapshotRepository;
        this.modelRepository = modelRepository;
        this.versionRepository = versionRepository;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
    }

    /** Lazily bootstraps main for projects created before version control was introduced. */
    @Transactional
    public VcsBranch ensureMain(UmlModel model, UserAccount author) {
        UmlModel managedModel = requireModel(model.getId());
        UUID activeId = activeBranchId(managedModel.getId());
        VcsBranch main = branchRepository.findByModel_IdAndName(managedModel.getId(), VcsBranch.MAIN).orElse(null);
        if (main == null) {
            String stateHash = stateHasher.hash(managedModel);
            SnapshotModel captured = snapshotService.capture(managedModel);
            VcsSnapshot snapshot = findOrCreateSnapshot(managedModel, stateHash, captured);
            VcsCommit initial = commitRepository.saveAndFlush(VcsCommit.create(managedModel, null, null, snapshot,
                    VcsBranch.MAIN, VcsCommitKind.INITIAL, VcsCommitOrigin.MANUAL, "Initial commit", author));
            main = branchRepository.saveAndFlush(VcsBranch.create(managedModel, VcsBranch.MAIN, initial, true, author));
        }
        if (activeId == null || branchRepository.findById(activeId)
                .filter(branch -> managedModel.getId().equals(branch.getModel().getId())).isEmpty()) {
            setActiveBranch(managedModel.getId(), main.getId());
        }
        return main;
    }

    @Transactional(readOnly = true)
    public List<VcsResources.Branch> list(UmlModel model) {
        UmlModel managedModel = requireModel(model.getId());
        return branchRepository.findByModel_IdOrderByNameAsc(managedModel.getId()).stream()
                .map(VcsBranchService::branchResource)
                .toList();
    }

    @Transactional
    public VcsResources.Branch create(UmlModel model, String name, String fromBranchName, UserAccount author) {
        UmlModel managedModel = requireModel(model.getId());
        UUID projectId = managedModel.getProject().getId();
        accessService.requireBranch(projectId);

        String normalizedName = normalizeNewBranchName(name);
        if (VcsBranch.MAIN.equals(normalizedName)) {
            throw branchNameTaken("The main branch already exists.");
        }
        if (branchRepository.findByModel_IdOrderByNameAsc(managedModel.getId()).stream()
                .anyMatch(branch -> branch.getName().equalsIgnoreCase(normalizedName))) {
            throw branchNameTaken("A branch with that name already exists.");
        }

        VcsBranch source = null;
        if (fromBranchName != null) {
            String normalizedSource = normalizeLookupName(fromBranchName);
            source = branchRepository.findByModel_IdAndName(managedModel.getId(), normalizedSource)
                    .orElseThrow(() -> notFound("Source branch was not found."));
        }
        ensureMain(managedModel, author);
        if (source == null) {
            source = activeBranch(managedModel);
        }
        VcsBranch created = branchRepository.saveAndFlush(VcsBranch.create(managedModel, normalizedName,
                source.getHeadCommit(), false, author));
        return branchResource(created);
    }

    @Transactional
    public VcsResources.Branch setProtection(UmlModel model, String name, boolean isProtected, UserAccount author) {
        UmlModel managedModel = requireModel(model.getId());
        accessService.requireProtection(managedModel.getProject().getId());
        ensureMain(managedModel, author);
        VcsBranch branch = findBranch(managedModel, name);
        branch.setProtected(isProtected);
        return branchResource(branchRepository.saveAndFlush(branch));
    }

    /** Restores a branch head and records that materialization as a new model revision. */
    @Transactional
    public UmlModel checkout(UmlModel model, String name, UserAccount author) {
        UUID modelId = model.getId();
        UmlModel managedModel = requireModel(modelId);
        UUID projectId = managedModel.getProject().getId();
        accessService.requireBranch(projectId);
        ensureMain(managedModel, author);
        VcsBranch target = findBranch(managedModel, name);
        VcsBranch current = activeBranch(managedModel);
        String workingHash = stateHasher.hash(managedModel);
        String currentHeadHash = current.getHeadCommit().getSnapshot().getStateHash();
        if (!Objects.equals(workingHash, currentHeadHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKING_COPY_DIRTY",
                    "Commit or discard working-copy changes before checking out another branch.", List.of(), null);
        }

        return materializeCommit(managedModel, target.getHeadCommit(), target.getId(), author);
    }

    /**
     * Materializes the commit's snapshot into the live model and records that as a new model revision. When
     * {@code newActiveBranchId} is not null that branch becomes the active one; otherwise the active branch is kept.
     */
    UmlModel materializeCommit(UmlModel managedModel, VcsCommit targetHead, UUID newActiveBranchId,
            UserAccount author) {
        UUID modelId = managedModel.getId();
        UUID projectId = managedModel.getProject().getId();
        SnapshotModel restored = snapshotService.fromJson(targetHead.getSnapshot().getPayload());
        String targetStateHash = targetHead.getSnapshot().getStateHash();
        long expectedRevision = managedModel.getCurrentRevision();
        snapshotService.materialize(managedModel, restored);

        // materialize clears the persistence context; all entities used below are freshly loaded.
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> notFound("No se encontró el proyecto."));
        UmlModel refreshedModel = requireModel(modelId);
        long nextRevision = expectedRevision + 1;
        if (modelRepository.bumpRevisionIfCurrent(modelId, expectedRevision, nextRevision) != 1) {
            throw new IllegalStateException("Model revision changed during snapshot materialization");
        }
        refreshedModel.setCurrentRevision(nextRevision);
        if (newActiveBranchId != null) {
            setActiveBranch(modelId, newActiveBranchId);
        }
        UserAccount refreshedAuthor = author == null ? null
                : userRepository.findById(author.getId()).orElse(null);
        versionRepository.saveAndFlush(ModelVersion.create(refreshedModel, nextRevision, expectedRevision,
                targetStateHash, ValidationState.VALID, refreshedAuthor));
        entityManager.flush();
        entityManager.clear();
        // Re-read both project and model after the materializer's clear so callers never observe stale entities.
        projectRepository.findById(project.getId()).orElseThrow(() -> notFound("No se encontró el proyecto."));
        return requireModel(modelId);
    }

    @Transactional(readOnly = true)
    public VcsBranch activeBranch(UmlModel model) {
        UmlModel managedModel = requireModel(model.getId());
        UUID branchId = activeBranchId(managedModel.getId());
        if (branchId != null) {
            VcsBranch branch = branchRepository.findById(branchId).orElse(null);
            if (branch != null && managedModel.getId().equals(branch.getModel().getId())) {
                return branch;
            }
        }
        return branchRepository.findByModel_IdAndName(managedModel.getId(), VcsBranch.MAIN)
                .orElseThrow(() -> notFound("The main branch was not found."));
    }

    private VcsSnapshot findOrCreateSnapshot(UmlModel model, String stateHash, SnapshotModel captured) {
        return snapshotRepository.findByModel_IdAndStateHash(model.getId(), stateHash)
                .orElseGet(() -> snapshotRepository.saveAndFlush(
                        VcsSnapshot.create(model, stateHash, snapshotService.toJson(captured))));
    }

    private VcsBranch findBranch(UmlModel model, String name) {
        return branchRepository.findByModel_IdAndName(model.getId(), normalizeLookupName(name))
                .orElseThrow(() -> notFound("Branch was not found."));
    }

    private UUID activeBranchId(UUID modelId) {
        Object value = entityManager.createNativeQuery(
                "SELECT active_branch_id FROM diagrammer.uml_model WHERE model_id = :modelId")
                .setParameter("modelId", modelId)
                .getSingleResult();
        return value == null ? null : (UUID) value;
    }

    private void setActiveBranch(UUID modelId, UUID branchId) {
        entityManager.createNativeQuery("UPDATE diagrammer.uml_model SET active_branch_id = :branchId "
                + "WHERE model_id = :modelId")
                .setParameter("branchId", branchId)
                .setParameter("modelId", modelId)
                .executeUpdate();
    }

    private UmlModel requireModel(UUID modelId) {
        return modelRepository.findById(modelId).orElseThrow(() -> notFound("No se encontró el modelo."));
    }

    private static VcsResources.Branch branchResource(VcsBranch branch) {
        VcsCommit head = branch.getHeadCommit();
        UserAccount author = head.getAuthor();
        return new VcsResources.Branch(branch.getName(), branch.isProtected(), head.getId(), head.getMessage(),
                author == null ? null : author.getDisplayName(), head.getCreatedAt());
    }

    private static String normalizeNewBranchName(String name) {
        if (name == null) {
            throw invalidPayload("Branch name is required.");
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 120) {
            throw invalidPayload("Branch name must contain 1 to 120 characters.");
        }
        return normalized;
    }

    private static String normalizeLookupName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException invalidPayload(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", message, List.of(), null);
    }

    private static ApiException branchNameTaken(String message) {
        return new ApiException(HttpStatus.CONFLICT, "BRANCH_NAME_TAKEN", message, List.of(), null);
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, List.of(), null);
    }
}
