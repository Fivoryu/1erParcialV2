package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.domain.Invitation;
import com.diagramador.domain.InvitationStatus;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.persistence.InvitationRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class InvitationApiTest extends AuthenticatedApiTest {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final InvitationRepository invitationRepository;
    private final ProjectMemberRepository projectMemberRepository;

    @Autowired
    InvitationApiTest(MockMvc mockMvc, ObjectMapper objectMapper, InvitationRepository invitationRepository,
            ProjectMemberRepository projectMemberRepository) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.invitationRepository = invitationRepository;
        this.projectMemberRepository = projectMemberRepository;
    }

    @org.junit.jupiter.api.Test
    void ownerInvitesEditorWhoAcceptsAndGainsEditorAccess() throws Exception {
        String owner = username("owner");
        String invitee = username("invitee");
        UUID projectId = createProject(owner, "accept-editor");
        bearer(invitee);

        MvcResult inviteResult = mockMvc.perform(authorizedAs(owner,
                post("/projects/{projectId}/invitations", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "username", invitee.toUpperCase(Locale.ROOT), "role", "EDITOR")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.inviteeUsername").value(invitee))
                .andExpect(jsonPath("$.role").value("EDITOR"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.invitedByUsername").value(owner))
                .andReturn();
        UUID invitationId = UUID.fromString(objectMapper.readTree(inviteResult.getResponse().getContentAsByteArray())
                .path("id").asText());

        mockMvc.perform(authorizedAs(owner, get("/projects/{projectId}/invitations", projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(invitationId.toString()));

        mockMvc.perform(authorizedAs(invitee, post("/projects/{projectId}/invitations/{invitationId}/accept",
                projectId, invitationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userIdFor(invitee).toString()))
                .andExpect(jsonPath("$.role").value("EDITOR"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(projectId, userIdFor(invitee))
                .orElseThrow();
        assertThat(member.getRole()).isEqualTo(ProjectMemberRole.EDITOR);
        assertThat(member.getStatus()).isEqualTo("ACTIVE");

        // This pre-existing member-only endpoint proves acceptance took effect outside its response body.
        mockMvc.perform(authorizedAs(invitee, get("/projects/{projectId}/model", projectId)))
                .andExpect(status().isOk());
        // This pre-existing editor-only endpoint proves that the invited role is effective authorization.
        mockMvc.perform(authorizedAs(invitee, post("/projects/{projectId}/generation-targets", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(targetRequest("invitation-editor")))))
                .andExpect(status().isOk());
    }

    @org.junit.jupiter.api.Test
    void duplicatePendingInvitationIsConflict() throws Exception {
        String owner = username("owner");
        String invitee = username("duplicate");
        UUID projectId = createProject(owner, "duplicate");
        bearer(invitee);
        MvcResult firstInvite = invite(owner, projectId, invitee, "VIEWER");
        assertThat(firstInvite.getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(authorizedAs(owner, post("/projects/{projectId}/invitations", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("username", invitee, "role", "EDITOR")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_EXISTS"));
    }

    @org.junit.jupiter.api.Test
    void unknownUsernameCannotBeInvited() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner, "unknown-user");

        mockMvc.perform(authorizedAs(owner, post("/projects/{projectId}/invitations", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("username", username("missing"), "role", "VIEWER")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @org.junit.jupiter.api.Test
    void nonOwnerCannotInviteListInvitationsOrChangeMemberRole() throws Exception {
        String owner = username("owner");
        String editor = username("editor");
        String invitee = username("target");
        UUID projectId = createProject(owner, "non-owner");
        bearer(editor);
        bearer(invitee);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        UUID ownerId = userIdFor(owner);

        mockMvc.perform(authorizedAs(editor, post("/projects/{projectId}/invitations", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("username", invitee, "role", "VIEWER")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(authorizedAs(editor, get("/projects/{projectId}/invitations", projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(authorizedAs(editor, patch("/projects/{projectId}/members/{userId}", projectId, ownerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("role", "VIEWER")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @org.junit.jupiter.api.Test
    void nonInviteeCannotAcceptInvitation() throws Exception {
        String owner = username("owner");
        String invitee = username("invitee");
        String outsider = username("outsider");
        UUID projectId = createProject(owner, "wrong-acceptor");
        bearer(invitee);
        bearer(outsider);
        UUID invitationId = invitationId(invite(owner, projectId, invitee, "VIEWER"));

        mockMvc.perform(authorizedAs(outsider,
                post("/projects/{projectId}/invitations/{invitationId}/accept", projectId, invitationId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @org.junit.jupiter.api.Test
    void ownerCannotChangeTheirOwnRole() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner, "owner-role");

        mockMvc.perform(authorizedAs(owner, patch("/projects/{projectId}/members/{userId}", projectId,
                userIdFor(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("role", "VIEWER")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @org.junit.jupiter.api.Test
    void ownerCannotInviteOwnerRole() throws Exception {
        String owner = username("owner");
        String invitee = username("owner-target");
        UUID projectId = createProject(owner, "owner-invitation");
        bearer(invitee);

        mockMvc.perform(authorizedAs(owner, post("/projects/{projectId}/invitations", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("username", invitee, "role", "OWNER")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"));
    }

    @org.junit.jupiter.api.Test
    void revokingInvitationPreservesItsRevokedRow() throws Exception {
        String owner = username("owner");
        String invitee = username("revoke-target");
        UUID projectId = createProject(owner, "revoke");
        bearer(invitee);
        UUID invitationId = invitationId(invite(owner, projectId, invitee, "EDITOR"));

        mockMvc.perform(authorizedAs(owner,
                post("/projects/{projectId}/invitations/{invitationId}/revoke", projectId, invitationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(invitationId.toString()))
                .andExpect(jsonPath("$.status").value("REVOKED"));

        Invitation stored = invitationRepository.findByProject_IdAndId(projectId, invitationId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(InvitationStatus.REVOKED);
    }

    @org.junit.jupiter.api.Test
    void acceptingInvitationReactivatesRemovedMembershipWithInvitedRole() throws Exception {
        String owner = username("owner");
        String invitee = username("reactivate");
        UUID projectId = createProject(owner, "reactivate");
        bearer(invitee);
        addMembership(invitee, projectId, ProjectMemberRole.VIEWER);
        ProjectMember removedMember = projectMemberRepository.findByProjectIdAndUserId(projectId, userIdFor(invitee))
                .orElseThrow();
        removedMember.setStatus("REMOVED");
        projectMemberRepository.saveAndFlush(removedMember);
        UUID invitationId = invitationId(invite(owner, projectId, invitee, "EDITOR"));

        mockMvc.perform(authorizedAs(invitee,
                post("/projects/{projectId}/invitations/{invitationId}/accept", projectId, invitationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("EDITOR"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        ProjectMember reactivated = projectMemberRepository.findByProjectIdAndUserId(projectId, userIdFor(invitee))
                .orElseThrow();
        assertThat(reactivated.getId().getProjectId()).isEqualTo(projectId);
        assertThat(reactivated.getId().getUserId()).isEqualTo(userIdFor(invitee));
        assertThat(reactivated.getRole()).isEqualTo(ProjectMemberRole.EDITOR);
        assertThat(reactivated.getStatus()).isEqualTo("ACTIVE");
    }

    @org.junit.jupiter.api.Test
    void invitingAnActiveMemberIsConflict() throws Exception {
        String owner = username("owner");
        String member = username("active-member");
        UUID projectId = createProject(owner, "active-member");
        bearer(member);
        addMembership(member, projectId, ProjectMemberRole.VIEWER);

        mockMvc.perform(authorizedAs(owner, post("/projects/{projectId}/invitations", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("username", member, "role", "EDITOR")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ALREADY_ACTIVE"));
    }

    @org.junit.jupiter.api.Test
    void ownerCanListMembersAndChangeAnotherMembersRole() throws Exception {
        String owner = username("owner");
        String member = username("listed-member");
        UUID projectId = createProject(owner, "list-members");
        bearer(member);
        addMembership(member, projectId, ProjectMemberRole.VIEWER);

        mockMvc.perform(authorizedAs(owner, get("/projects/{projectId}/members", projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").exists())
                .andExpect(jsonPath("$[0].username").exists())
                .andExpect(jsonPath("$[0].displayName").exists())
                .andExpect(jsonPath("$[0].role").exists())
                .andExpect(jsonPath("$[0].status").exists());
        mockMvc.perform(authorizedAs(owner, patch("/projects/{projectId}/members/{userId}", projectId,
                userIdFor(member))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("role", "EDITOR")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("EDITOR"));
    }

    @org.junit.jupiter.api.Test
    void ownerRemovesMemberAndTheirAccessIsRevokedWithoutLosingOwnerAccess() throws Exception {
        String owner = username("owner");
        String member = username("removed-member");
        UUID projectId = createProject(owner, "remove-member");
        bearer(member);
        addMembership(member, projectId, ProjectMemberRole.EDITOR);
        ProjectMember beforeRemoval = projectMemberRepository.findByProjectIdAndUserId(projectId, userIdFor(member))
                .orElseThrow();

        mockMvc.perform(authorizedAs(member, get("/projects/{projectId}/model", projectId)))
                .andExpect(status().isOk());
        mockMvc.perform(authorizedAs(owner, delete("/projects/{projectId}/members/{userId}", projectId,
                userIdFor(member))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userIdFor(member).toString()))
                .andExpect(jsonPath("$.status").value("REMOVED"));

        ProjectMember removed = projectMemberRepository.findByProjectIdAndUserId(projectId, userIdFor(member))
                .orElseThrow();
        assertThat(removed.getStatus()).isEqualTo("REMOVED");
        assertThat(removed.getUpdatedAt()).isAfter(beforeRemoval.getUpdatedAt());
        mockMvc.perform(authorizedAs(member, get("/projects/{projectId}/model", projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(authorizedAs(owner, get("/projects/{projectId}/model", projectId)))
                .andExpect(status().isOk());
    }

    @org.junit.jupiter.api.Test
    void ownerCannotRemoveThemselves() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner, "self-remove");

        mockMvc.perform(authorizedAs(owner, delete("/projects/{projectId}/members/{userId}", projectId,
                userIdFor(owner))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @org.junit.jupiter.api.Test
    void removingAlreadyRemovedMemberIsConflict() throws Exception {
        String owner = username("owner");
        String member = username("already-removed");
        UUID projectId = createProject(owner, "already-removed");
        bearer(member);
        addMembership(member, projectId, ProjectMemberRole.VIEWER);
        ProjectMember removed = projectMemberRepository.findByProjectIdAndUserId(projectId, userIdFor(member))
                .orElseThrow();
        removed.setStatus("REMOVED");
        projectMemberRepository.saveAndFlush(removed);

        mockMvc.perform(authorizedAs(owner, delete("/projects/{projectId}/members/{userId}", projectId,
                userIdFor(member))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_ACTIVE"));
    }

    @org.junit.jupiter.api.Test
    void nonOwnerCannotRemoveMember() throws Exception {
        String owner = username("owner");
        String editor = username("editor");
        String member = username("member");
        UUID projectId = createProject(owner, "non-owner-remove");
        bearer(editor);
        bearer(member);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        addMembership(member, projectId, ProjectMemberRole.VIEWER);

        mockMvc.perform(authorizedAs(editor, delete("/projects/{projectId}/members/{userId}", projectId,
                userIdFor(member))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private MvcResult invite(String owner, UUID projectId, String invitee, String role) throws Exception {
        return mockMvc.perform(authorizedAs(owner, post("/projects/{projectId}/invitations", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("username", invitee, "role", role)))))
                .andReturn();
    }

    private UUID invitationId(MvcResult result) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return UUID.fromString(response.path("id").asText());
    }

    private UUID createProject(String owner, String label) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                        "name", "Invitation test " + label,
                        "description", "Invitation API integration test")))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText());
    }

    private Map<String, Object> targetRequest(String key) {
        return Map.of(
                "targetKey", key + "-" + UUID.randomUUID().toString().substring(0, 8),
                "name", "Target " + key,
                "databaseEngine", "POSTGRESQL",
                "databaseName", "invitation_test_database",
                "databaseSchema", "public");
    }

    private String username(String prefix) {
        return "invitation-" + prefix + "-" + UUID.randomUUID();
    }
}
