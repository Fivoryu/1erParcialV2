package com.diagramador.api;

import com.diagramador.domain.InvitationStatus;
import com.diagramador.domain.ProjectMemberRole;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class InvitationResources {

    private InvitationResources() {
    }

    public record MemberResource(UUID userId, String username, String displayName, ProjectMemberRole role,
            String status) {
    }

    public record InvitationResource(UUID id, String inviteeUsername, String inviteeDisplayName,
            ProjectMemberRole role, InvitationStatus status, String invitedByUsername, OffsetDateTime createdAt) {
    }

    public record CreateInvitationRequest(String username, String role) {
    }

    public record ChangeMemberRoleRequest(String role) {
    }
}
