package com.diagramador.vcs;

import com.diagramador.api.ApiException;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.persistence.ProjectMemberRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** The authorization seam for version-control operations; fine-grained grants belong here. */
@Service
public class VcsAccessService {

    private final ProjectMemberRepository projectMemberRepository;
    private final CurrentUserContext currentUserContext;

    public VcsAccessService(ProjectMemberRepository projectMemberRepository, CurrentUserContext currentUserContext) {
        this.projectMemberRepository = projectMemberRepository;
        this.currentUserContext = currentUserContext;
    }

    public void requireRead(UUID projectId) {
        if (currentUserContext.isOperator()) {
            return;
        }
        requireActiveMember(projectId);
    }

    public void requireCommit(UUID projectId) {
        if (currentUserContext.isOperator()) {
            return;
        }
        ProjectMember member = requireActiveMember(projectId);
        if (member.getRole() != ProjectMemberRole.OWNER && member.getRole() != ProjectMemberRole.EDITOR) {
            throw forbidden("Committing requires EDITOR or OWNER access to this project.");
        }
    }

    public void requireBranch(UUID projectId) {
        requireCommit(projectId);
    }

    public void requireProtection(UUID projectId) {
        if (currentUserContext.isOperator()) {
            return;
        }
        ProjectMember member = requireActiveMember(projectId);
        if (member.getRole() != ProjectMemberRole.OWNER) {
            throw forbidden("Changing branch protection requires OWNER access to this project.");
        }
    }

    private ProjectMember requireActiveMember(UUID projectId) {
        UUID userId = currentUserContext.require().userId();
        return projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .filter(member -> "ACTIVE".equals(member.getStatus()))
                .orElseThrow(() -> forbidden("You do not have access to this project's version history."));
    }

    private static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message, List.of(), null);
    }
}
