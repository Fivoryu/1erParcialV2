package com.diagramador.api;

import com.diagramador.api.InvitationResources.ChangeMemberRoleRequest;
import com.diagramador.api.InvitationResources.CreateInvitationRequest;
import com.diagramador.api.InvitationResources.InvitationResource;
import com.diagramador.api.InvitationResources.MemberResource;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class InvitationController {

    private final InvitationApiService invitationApiService;

    public InvitationController(InvitationApiService invitationApiService) {
        this.invitationApiService = invitationApiService;
    }

    @GetMapping("/projects/{projectId}/members")
    public List<MemberResource> listMembers(@PathVariable("projectId") UUID projectId) {
        return invitationApiService.listMembers(projectId);
    }

    @GetMapping("/projects/{projectId}/invitations")
    public List<InvitationResource> listInvitations(@PathVariable("projectId") UUID projectId) {
        return invitationApiService.listInvitations(projectId);
    }

    @GetMapping("/invitations")
    public List<InvitationResource> listOwnInvitations() {
        return invitationApiService.listOwnInvitations();
    }

    @PostMapping(path = "/projects/{projectId}/invitations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InvitationResource> createInvitation(@PathVariable("projectId") UUID projectId,
            @RequestBody CreateInvitationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(invitationApiService.createInvitation(projectId, request));
    }

    @PostMapping(path = "/projects/{projectId}/invitations/{invitationId}/accept")
    public MemberResource acceptInvitation(@PathVariable("projectId") UUID projectId,
            @PathVariable("invitationId") UUID invitationId) {
        return invitationApiService.acceptInvitation(projectId, invitationId);
    }

    @PostMapping(path = "/projects/{projectId}/invitations/{invitationId}/decline")
    public InvitationResource declineInvitation(@PathVariable("projectId") UUID projectId,
            @PathVariable("invitationId") UUID invitationId) {
        return invitationApiService.declineInvitation(projectId, invitationId);
    }

    @PostMapping(path = "/projects/{projectId}/invitations/{invitationId}/revoke")
    public InvitationResource revokeInvitation(@PathVariable("projectId") UUID projectId,
            @PathVariable("invitationId") UUID invitationId) {
        return invitationApiService.revokeInvitation(projectId, invitationId);
    }

    @PatchMapping(path = "/projects/{projectId}/members/{userId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MemberResource changeMemberRole(@PathVariable("projectId") UUID projectId,
            @PathVariable("userId") UUID userId,
            @RequestBody ChangeMemberRoleRequest request) {
        return invitationApiService.changeMemberRole(projectId, userId, request);
    }

    @DeleteMapping("/projects/{projectId}/members/{userId}")
    public MemberResource removeMember(@PathVariable("projectId") UUID projectId,
            @PathVariable("userId") UUID userId) {
        return invitationApiService.removeMember(projectId, userId);
    }
}
