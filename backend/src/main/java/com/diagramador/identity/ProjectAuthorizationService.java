package com.diagramador.identity;

import com.diagramador.api.ApiException;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.persistence.ProjectMemberRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProjectAuthorizationService {

    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String NO_PROJECT_ACCESS = "No tienes acceso a este proyecto.";
    private static final String EDIT_ACCESS_REQUIRED = "This action requires edit access to this project.";
    private static final String ADMINISTRATION_REQUIRED = "This action requires administration of this project.";

    private final ProjectMemberRepository projectMemberRepository;
    private final CurrentUserContext currentUserContext;

    public ProjectAuthorizationService(ProjectMemberRepository projectMemberRepository,
            CurrentUserContext currentUserContext) {
        this.projectMemberRepository = projectMemberRepository;
        this.currentUserContext = currentUserContext;
    }

    public void requireMember(UUID projectId) {
        if (currentUserContext.isOperator()) {
            return;
        }
        requireActiveMember(projectId);
    }

    /** Read-only access: members, the operator and platform administrators. Never use it for writes. */
    public void requireProjectRead(UUID projectId) {
        if (currentUserContext.isOperator()) {
            return;
        }
        if (projectMemberRepository.isPlatformAdmin(currentUserContext.require().userId())) {
            return;
        }
        requireActiveMember(projectId);
    }

    public void requireEditor(UUID projectId) {
        if (currentUserContext.isOperator()) {
            return;
        }
        ProjectMember member = requireActiveMember(projectId);
        if (member.getRole() != ProjectMemberRole.OWNER && member.getRole() != ProjectMemberRole.EDITOR) {
            throw forbidden(EDIT_ACCESS_REQUIRED);
        }
    }

    public void requireOwner(UUID projectId) {
        if (currentUserContext.isOperator()) {
            throw forbidden(ADMINISTRATION_REQUIRED);
        }
        ProjectMember member = requireActiveMember(projectId);
        if (member.getRole() != ProjectMemberRole.OWNER) {
            throw forbidden(ADMINISTRATION_REQUIRED);
        }
    }

    public void requireOperator() {
        if (!currentUserContext.isOperator()) {
            throw forbidden("The operator principal is required.");
        }
    }

    private ProjectMember requireActiveMember(UUID projectId) {
        UUID userId = currentUserContext.require().userId();
        Optional<ProjectMember> member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .filter(candidate -> ACTIVE_STATUS.equals(candidate.getStatus()));
        return member.orElseThrow(() -> forbidden(NO_PROJECT_ACCESS));
    }

    private static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message, List.of(), null);
    }
}
