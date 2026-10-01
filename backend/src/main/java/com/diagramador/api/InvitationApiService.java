package com.diagramador.api;

import com.diagramador.api.InvitationResources.ChangeMemberRoleRequest;
import com.diagramador.api.InvitationResources.CreateInvitationRequest;
import com.diagramador.api.InvitationResources.InvitationResource;
import com.diagramador.api.InvitationResources.MemberResource;
import com.diagramador.domain.Invitation;
import com.diagramador.domain.InvitationStatus;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.UserAccount;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.InvitationRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.UserAccountRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationApiService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final InvitationRepository invitationRepository;
    private final UserAccountRepository userAccountRepository;
    private final CurrentUserContext currentUserContext;
    private final ProjectAuthorizationService projectAuthorizationService;

    public InvitationApiService(ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository, InvitationRepository invitationRepository,
            UserAccountRepository userAccountRepository, CurrentUserContext currentUserContext,
            ProjectAuthorizationService projectAuthorizationService) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.invitationRepository = invitationRepository;
        this.userAccountRepository = userAccountRepository;
        this.currentUserContext = currentUserContext;
        this.projectAuthorizationService = projectAuthorizationService;
    }

    @Transactional(readOnly = true)
    public List<MemberResource> listMembers(UUID projectId) {
        requireProject(projectId);
        projectAuthorizationService.requireMember(projectId);
        return projectMemberRepository.findByProjectId(projectId).stream()
                .sorted((left, right) -> {
                    int byUsername = String.CASE_INSENSITIVE_ORDER.compare(
                            left.getUser().getUsername(), right.getUser().getUsername());
                    return byUsername != 0 ? byUsername
                            : left.getId().getUserId().compareTo(right.getId().getUserId());
                })
                .map(InvitationApiService::memberResource)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InvitationResource> listInvitations(UUID projectId) {
        requireProject(projectId);
        projectAuthorizationService.requireOwner(projectId);
        return invitationRepository.findByProject_IdOrderByCreatedAtAscIdAsc(projectId).stream()
                .map(InvitationApiService::invitationResource)
                .toList();
    }

    @Transactional
    public InvitationResource createInvitation(UUID projectId, CreateInvitationRequest request) {
        Project project = requireProject(projectId);
        projectAuthorizationService.requireOwner(projectId);
        if (request == null || request.username() == null || request.username().isBlank()) {
            throw invalidPayload("A non-blank username is required.");
        }
        ProjectMemberRole role = assignableRole(request.role());
        String username = request.username().trim();
        UserAccount invitee = userAccountRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The user account was not found."));

        projectMemberRepository.findByProjectIdAndUserId(projectId, invitee.getId())
                .filter(member -> "ACTIVE".equals(member.getStatus()))
                .ifPresent(member -> {
                    throw error(HttpStatus.CONFLICT, "MEMBER_ALREADY_ACTIVE",
                            "The user is already an active member of this project.");
                });
        if (invitationRepository.existsByProject_IdAndInvitee_IdAndStatus(projectId, invitee.getId(),
                InvitationStatus.PENDING)) {
            throw error(HttpStatus.CONFLICT, "INVITATION_EXISTS",
                    "A pending invitation already exists for this user and project.");
        }

        UserAccount inviter = userAccountRepository.findById(currentUserContext.require().userId())
                .orElseThrow(() -> error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                        "The authenticated user account was not found."));
        Invitation invitation = invitationRepository.saveAndFlush(Invitation.create(project, inviter, invitee, role));
        return invitationResource(invitation);
    }

    @Transactional
    public MemberResource acceptInvitation(UUID projectId, UUID invitationId) {
        requireProject(projectId);
        Invitation invitation = requireInvitation(projectId, invitationId);
        UUID requesterId = currentUserContext.require().userId();
        if (invitation.getInvitee() == null || !requesterId.equals(invitation.getInvitee().getId())) {
            throw error(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the invited user may accept this invitation.");
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw error(HttpStatus.CONFLICT, "INVITATION_NOT_PENDING", "The invitation is no longer pending.");
        }

        UserAccount invitee = invitation.getInvitee();
        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(projectId, requesterId)
                .orElse(null);
        if (member == null) {
            member = ProjectMember.create(invitation.getProject(), invitee, invitation.getRole());
        } else if ("ACTIVE".equals(member.getStatus())) {
            throw error(HttpStatus.CONFLICT, "MEMBER_ALREADY_ACTIVE",
                    "The invited user is already an active member of this project.");
        } else {
            member.setRole(invitation.getRole());
            member.setStatus("ACTIVE");
            member.setUpdatedAt(OffsetDateTime.now());
        }

        ProjectMember savedMember = projectMemberRepository.save(member);
        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setUpdatedAt(OffsetDateTime.now());
        invitationRepository.save(invitation);
        return memberResource(savedMember);
    }

    @Transactional
    public InvitationResource revokeInvitation(UUID projectId, UUID invitationId) {
        requireProject(projectId);
        projectAuthorizationService.requireOwner(projectId);
        Invitation invitation = requireInvitation(projectId, invitationId);
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw error(HttpStatus.CONFLICT, "INVITATION_NOT_PENDING", "The invitation is no longer pending.");
        }
        invitation.setStatus(InvitationStatus.REVOKED);
        invitation.setUpdatedAt(OffsetDateTime.now());
        return invitationResource(invitationRepository.save(invitation));
    }

    @Transactional
    public MemberResource changeMemberRole(UUID projectId, UUID userId, ChangeMemberRoleRequest request) {
        requireProject(projectId);
        projectAuthorizationService.requireOwner(projectId);
        if (currentUserContext.require().userId().equals(userId)) {
            throw error(HttpStatus.FORBIDDEN, "FORBIDDEN", "The project owner cannot change their own role.");
        }
        ProjectMemberRole role = assignableRole(request == null ? null : request.role());
        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND",
                        "The project member was not found."));
        member.setRole(role);
        member.setUpdatedAt(OffsetDateTime.now());
        return memberResource(projectMemberRepository.save(member));
    }

    @Transactional
    public MemberResource removeMember(UUID projectId, UUID userId) {
        requireProject(projectId);
        projectAuthorizationService.requireOwner(projectId);
        if (currentUserContext.require().userId().equals(userId)) {
            throw error(HttpStatus.FORBIDDEN, "FORBIDDEN", "The project owner cannot remove themselves.");
        }
        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND",
                        "The project member was not found."));
        if (!"ACTIVE".equals(member.getStatus())) {
            throw error(HttpStatus.CONFLICT, "MEMBER_NOT_ACTIVE", "The project member is not active.");
        }
        member.setStatus("REMOVED");
        member.setUpdatedAt(OffsetDateTime.now());
        return memberResource(projectMemberRepository.save(member));
    }

    private Project requireProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "The project was not found."));
    }

    private Invitation requireInvitation(UUID projectId, UUID invitationId) {
        return invitationRepository.findByProject_IdAndId(projectId, invitationId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND",
                        "The invitation was not found for this project."));
    }

    private static ProjectMemberRole assignableRole(String role) {
        if ("VIEWER".equals(role)) {
            return ProjectMemberRole.VIEWER;
        }
        if ("EDITOR".equals(role)) {
            return ProjectMemberRole.EDITOR;
        }
        throw invalidPayload("The role must be VIEWER or EDITOR.");
    }

    private static MemberResource memberResource(ProjectMember member) {
        UserAccount user = member.getUser();
        return new MemberResource(user.getId(), user.getUsername(), user.getDisplayName(), member.getRole(),
                member.getStatus());
    }

    private static InvitationResource invitationResource(Invitation invitation) {
        UserAccount invitee = invitation.getInvitee();
        return new InvitationResource(invitation.getId(), invitee == null ? null : invitee.getUsername(),
                invitee == null ? null : invitee.getDisplayName(), invitation.getRole(), invitation.getStatus(),
                invitation.getInvitedBy().getUsername(), invitation.getCreatedAt());
    }

    private static ApiException invalidPayload(String message) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", message);
    }

    private static ApiException error(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message, List.of(), null);
    }
}
