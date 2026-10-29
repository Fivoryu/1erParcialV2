package com.diagramador.vcs;

import com.diagramador.api.ApiException;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.domain.VcsAction;
import com.diagramador.domain.VcsPermission;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.VcsPermissionRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and replaces per-diagram permission grants and their effective matrix view. */
@Service
public class VcsPermissionService {

    private final VcsPermissionRepository permissionRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final VcsAccessService accessService;

    public VcsPermissionService(VcsPermissionRepository permissionRepository,
            ProjectMemberRepository projectMemberRepository, VcsAccessService accessService) {
        this.permissionRepository = permissionRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.accessService = accessService;
    }

    /**
     * Lists effective actions, not stored grants, for active project members only. Non-owner members
     * see the same project-member directory they can already access; this never exposes other accounts.
     */
    @Transactional(readOnly = true)
    public List<VcsResources.Permission> list(UmlModel model, UserAccount actor) {
        if (actor != null) {
            projectMemberRepository.findByProjectIdAndUserId(model.getProject().getId(), actor.getId())
                    .filter(member -> "ACTIVE".equals(member.getStatus()))
                    .orElseThrow(() -> forbidden("No tienes acceso a los permisos de este proyecto."));
        }
        return projectMemberRepository.findByProjectId(model.getProject().getId()).stream()
                .filter(member -> "ACTIVE".equals(member.getStatus()))
                .sorted(Comparator.comparing((ProjectMember member) -> member.getUser().getUsername(),
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(member -> member.getId().getUserId()))
                .map(member -> resource(model, member))
                .toList();
    }

    /**
     * Replaces the stored grant rows for a member; {@code actions} are grant names, not effective actions.
     * Saving a list copied from the effective matrix turns it into explicit configuration: that set then
     * governs CREATE/ADD/DELETE instead of falling back to the project role. An empty list clears grants
     * and restores role-based behavior; it does not deny every action.
     */
    @Transactional
    public VcsResources.Permission replace(UmlModel model, UUID userId, List<String> actions, UserAccount actor) {
        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(model.getProject().getId(), userId)
                .filter(candidate -> "ACTIVE".equals(candidate.getStatus()))
                .orElseThrow(() -> notFound("The active project member was not found."));
        if (actions == null) {
            throw invalidPayload("The actions list is required.");
        }

        Set<VcsAction> requested = EnumSet.noneOf(VcsAction.class);
        for (String actionName : actions) {
            VcsAction action;
            try {
                action = VcsAction.valueOf(actionName);
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw invalidPayload("Unsupported permission action '" + String.valueOf(actionName) + "'.");
            }
            if (!accessService.actionWithinRoleCeiling(member.getRole(), action)) {
                throw invalidPayload("Permission action '" + action + "' exceeds the " + member.getRole()
                        + " role ceiling.");
            }
            requested.add(action);
        }

        List<VcsPermission> existing = permissionRepository.findByModel_IdAndUser_Id(model.getId(), userId);
        List<VcsPermission> removed = existing.stream()
                .filter(permission -> !requested.contains(permission.getAction()))
                .toList();
        permissionRepository.deleteAll(removed);

        Set<VcsAction> alreadyGranted = existing.stream()
                .map(VcsPermission::getAction)
                .collect(() -> EnumSet.noneOf(VcsAction.class), Set::add, Set::addAll);
        List<VcsPermission> inserted = new ArrayList<>();
        for (VcsAction action : requested) {
            if (!alreadyGranted.contains(action)) {
                inserted.add(VcsPermission.create(model, member.getUser(), action, actor));
            }
        }
        permissionRepository.saveAll(inserted);
        return resource(model, member);
    }

    private VcsResources.Permission resource(UmlModel model, ProjectMember member) {
        UserAccount user = member.getUser();
        List<VcsAction> effectiveActions = accessService.effectiveActions(model, member).stream()
                .sorted(Comparator.comparingInt(VcsAction::ordinal))
                .toList();
        return new VcsResources.Permission(user.getId(), user.getUsername(), user.getDisplayName(), effectiveActions);
    }

    private static ApiException invalidPayload(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", message, List.of(), null);
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, List.of(), null);
    }

    private static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message, List.of(), null);
    }
}
