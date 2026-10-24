package com.diagramador.vcs;

import com.diagramador.api.ApiException;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.VcsAction;
import com.diagramador.domain.VcsBranch;
import com.diagramador.domain.VcsPermission;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.VcsBranchRepository;
import com.diagramador.persistence.VcsPermissionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** The single authorization seam for version-control and diagram-content actions. */
@Service
public class VcsAccessService {

    private static final String ACTIVE_STATUS = "ACTIVE";

    private final ProjectMemberRepository projectMemberRepository;
    private final CurrentUserContext currentUserContext;
    private final UmlModelRepository modelRepository;
    private final VcsPermissionRepository permissionRepository;
    private final VcsBranchRepository branchRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public VcsAccessService(ProjectMemberRepository projectMemberRepository, CurrentUserContext currentUserContext,
            UmlModelRepository modelRepository, VcsPermissionRepository permissionRepository,
            VcsBranchRepository branchRepository) {
        this.projectMemberRepository = projectMemberRepository;
        this.currentUserContext = currentUserContext;
        this.modelRepository = modelRepository;
        this.permissionRepository = permissionRepository;
        this.branchRepository = branchRepository;
    }

    /** Enforces one action for the authenticated principal; the operator bypasses every check. */
    public void requireAction(UUID projectId, VcsAction action) {
        if (currentUserContext.isOperator()) {
            return;
        }
        ProjectMember member = requireActiveMember(projectId);
        UmlModel model = requireModel(projectId);
        Set<VcsAction> grants = grantedActions(model.getId(), member.getId().getUserId());
        boolean protectedBranch = action == VcsAction.COMMIT && activeBranchIsProtected(model);
        if (!canPerform(member, action, grants, protectedBranch)) {
            throw forbidden("You are not allowed to perform this action on this diagram.");
        }
    }

    public void requireRead(UUID projectId) {
        requireAction(projectId, VcsAction.VIEW);
    }

    public void requireCommit(UUID projectId) {
        requireAction(projectId, VcsAction.COMMIT);
    }

    public void requireBranch(UUID projectId) {
        requireAction(projectId, VcsAction.BRANCH);
    }

    public void requireMerge(UUID projectId) {
        requireAction(projectId, VcsAction.MERGE);
    }

    /** Branch-protection changes are permission management, not protected-branch commits. */
    public void requireProtection(UUID projectId) {
        requireAction(projectId, VcsAction.MANAGE_PERMISSIONS);
    }

    /** Whether an explicit grant is inside the recipient's project-role ceiling. */
    public boolean actionWithinRoleCeiling(ProjectMemberRole role, VcsAction action) {
        return switch (role) {
            case VIEWER -> action == VcsAction.VIEW;
            case EDITOR -> switch (action) {
                case VIEW, CREATE, ADD, DELETE, COMMIT, BRANCH -> true;
                case MERGE, MANAGE_PERMISSIONS -> false;
            };
            case OWNER -> true;
        };
    }

    /**
     * Returns a member's effective actions in declaration order for the permissions matrix.
     * COMMIT is intentionally branch-independent here: the matrix shows access to protected
     * main, while the actual commit check re-evaluates the active branch.
     */
    public List<VcsAction> effectiveActions(UmlModel model, ProjectMember member) {
        if (!ACTIVE_STATUS.equals(member.getStatus())) {
            return List.of();
        }
        Set<VcsAction> grants = grantedActions(model.getId(), member.getId().getUserId());
        boolean hasAnyGrant = !grants.isEmpty();
        boolean owner = member.getRole() == ProjectMemberRole.OWNER;
        return Arrays.stream(VcsAction.values())
                .filter(action -> actionWithinRoleCeiling(member.getRole(), action))
                .filter(action -> switch (action) {
                    case VIEW -> true;
                    case CREATE, ADD, DELETE -> owner || (member.getRole() == ProjectMemberRole.EDITOR
                            && (hasAnyGrant ? grants.contains(action) : true));
                    case COMMIT -> owner || (member.getRole() == ProjectMemberRole.EDITOR
                            && grants.contains(VcsAction.COMMIT));
                    case BRANCH -> owner || member.getRole() == ProjectMemberRole.EDITOR;
                    case MERGE, MANAGE_PERMISSIONS -> owner;
                })
                .toList();
    }

    private boolean canPerform(ProjectMember member, VcsAction action, Set<VcsAction> grants,
            boolean protectedBranch) {
        if (!actionWithinRoleCeiling(member.getRole(), action)) {
            return false;
        }
        if (member.getRole() == ProjectMemberRole.OWNER) {
            return true;
        }
        return switch (action) {
            case VIEW -> true;
            case CREATE, ADD, DELETE -> member.getRole() == ProjectMemberRole.EDITOR
                    && (grants.isEmpty() || grants.contains(action));
            case COMMIT -> member.getRole() == ProjectMemberRole.EDITOR
                    && (!protectedBranch || grants.contains(VcsAction.COMMIT));
            case BRANCH -> member.getRole() == ProjectMemberRole.EDITOR;
            case MERGE, MANAGE_PERMISSIONS -> false;
        };
    }

    private Set<VcsAction> grantedActions(UUID modelId, UUID userId) {
        return permissionRepository.findByModel_IdAndUser_Id(modelId, userId).stream()
                .map(VcsPermission::getAction)
                .collect(Collectors.toSet());
    }

    private boolean activeBranchIsProtected(UmlModel model) {
        Object activeBranchId = entityManager.createNativeQuery(
                "SELECT active_branch_id FROM diagrammer.uml_model WHERE model_id = :modelId")
                .setParameter("modelId", model.getId())
                .getSingleResult();
        if (activeBranchId != null) {
            return branchRepository.findById((UUID) activeBranchId)
                    .map(VcsBranch::isProtected)
                    .orElse(true);
        }
        // A not-yet-bootstrapped diagram receives a protected main on its first VCS request.
        return branchRepository.findByModel_IdAndName(model.getId(), VcsBranch.MAIN)
                .map(VcsBranch::isProtected)
                .orElse(true);
    }

    private ProjectMember requireActiveMember(UUID projectId) {
        UUID userId = currentUserContext.require().userId();
        return projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .filter(member -> ACTIVE_STATUS.equals(member.getStatus()))
                .orElseThrow(() -> forbidden("You do not have access to this project's version history."));
    }

    private UmlModel requireModel(UUID projectId) {
        return modelRepository.findByProjectId(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Model was not found.", List.of(), null));
    }

    private static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message, List.of(), null);
    }
}
