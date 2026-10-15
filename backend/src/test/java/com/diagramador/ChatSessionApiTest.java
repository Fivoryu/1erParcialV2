package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.domain.ProjectMemberRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Contract tests for diagram-scoped chat sessions (T2 of odd/tasks/chat-ia-colaborativo-web.md).
 * Contracts are fixed by PD-02 (L33) and PD-06 (L34): sessions belong to a DiagramView, human
 * messages never invoke the AI provider, foreign session ids are rejected without leaking
 * content, and deleting a session's history is a physical per-session delete reserved to OWNER.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatSessionApiTest extends AuthenticatedApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createdSessionsAreListedAndDoNotCopyOtherSessionHistory() throws Exception {
        String owner = username("sessions-owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);

        MvcResult created = mockMvc.perform(authorizedAs(owner, createSessionRequest(projectId, viewId, "Diseño")))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode session = objectMapper.readTree(created.getResponse().getContentAsByteArray());

        assertThat(session.path("id").asText()).isNotBlank();
        assertThat(session.path("viewId").asText()).isEqualTo(viewId.toString());
        assertThat(session.path("title").asText()).isEqualTo("Diseño");
        assertThat(session.path("createdByUserId").asText()).isEqualTo(userIdFor(owner).toString());
        assertThat(session.path("createdAt").asText()).isNotBlank();

        // The legacy endpoint writes into the compatibility session, not into the new one.
        postLegacyMessage(owner, projectId, "hola general");

        JsonNode sessions = listSessions(owner, projectId, viewId);
        assertThat(sessions).hasSize(2);
        assertThat(sessions).extracting(entry -> entry.path("title").asText())
                .containsExactlyInAnyOrder("General", "Diseño");

        JsonNode newSessionHistory = readSessionMessages(owner, projectId,
                UUID.fromString(session.path("id").asText()));
        assertThat(newSessionHistory).isEmpty();

        JsonNode legacyHistory = readLegacyHistory(owner, projectId);
        assertThat(legacyHistory).extracting(message -> message.path("body").asText())
                .containsExactly("hola general");
    }

    @Test
    void foreignSessionIdsAreRejectedWithoutLeakingContent() throws Exception {
        String ownerA = username("foreign-a");
        UUID projectA = createProject(ownerA);
        UUID viewA = defaultViewId(projectA);
        JsonNode sessionA = createSession(ownerA, projectA, viewA, "De A");
        postSessionMessage(ownerA, projectA, sessionId(sessionA), "secreto de A");

        String ownerB = username("foreign-b");
        UUID projectB = createProject(ownerB);

        mockMvc.perform(authorizedAs(ownerB,
                        get("/projects/{projectId}/sessions/{sessionId}/messages", projectB, sessionId(sessionA))))
                .andExpect(status().isNotFound());
        mockMvc.perform(authorizedAs(ownerB, postSessionMessageRequest(projectB, sessionId(sessionA), "intruso")))
                .andExpect(status().isNotFound());
        mockMvc.perform(authorizedAs(ownerB, delete("/projects/{projectId}/sessions/{sessionId}",
                        projectB, sessionId(sessionA))))
                .andExpect(status().isNotFound());

        // The real owner still reaches the session through its own project path.
        JsonNode ownHistory = readSessionMessages(ownerA, projectA, sessionId(sessionA));
        assertThat(ownHistory).extracting(message -> message.path("body").asText()).containsExactly("secreto de A");

        // Non-members are forbidden on the correct path (no existence information is added by this).
        String outsider = username("foreign-outsider");
        bearer(outsider);
        mockMvc.perform(authorizedAs(outsider,
                        get("/projects/{projectId}/sessions/{sessionId}/messages", projectA, sessionId(sessionA))))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletingASessionClearsOnlyItsHistoryForOwner() throws Exception {
        String owner = username("delete-owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        JsonNode session1 = createSession(owner, projectId, viewId, "Uno");
        JsonNode session2 = createSession(owner, projectId, viewId, "Dos");
        postSessionMessage(owner, projectId, sessionId(session1), "uno");
        postSessionMessage(owner, projectId, sessionId(session2), "dos");

        MvcResult deleted = mockMvc.perform(authorizedAs(owner,
                        delete("/projects/{projectId}/sessions/{sessionId}", projectId, sessionId(session1))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode deletion = objectMapper.readTree(deleted.getResponse().getContentAsByteArray());
        assertThat(deletion.path("deletedMessages").asInt()).isEqualTo(1);

        assertThat(readSessionMessages(owner, projectId, sessionId(session1))).isEmpty();
        assertThat(readSessionMessages(owner, projectId, sessionId(session2)))
                .extracting(message -> message.path("body").asText()).containsExactly("dos");
        // The session survives empty and reusable (PD-06: physical message delete, per session).
        JsonNode sessions = listSessions(owner, projectId, viewId);
        assertThat(sessions).extracting(entry -> entry.path("title").asText())
                .containsExactlyInAnyOrder("General", "Uno", "Dos");
    }

    @Test
    void onlyOwnerCanDeleteASessionHistory() throws Exception {
        String owner = username("perm-owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        JsonNode session = createSession(owner, projectId, viewId, "Borrable");
        postSessionMessage(owner, projectId, sessionId(session), "a borrar");

        String editor = username("perm-editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        String viewer = username("perm-viewer");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);

        mockMvc.perform(authorizedAs(editor,
                        delete("/projects/{projectId}/sessions/{sessionId}", projectId, sessionId(session))))
                .andExpect(status().isForbidden());
        mockMvc.perform(authorizedAs(viewer,
                        delete("/projects/{projectId}/sessions/{sessionId}", projectId, sessionId(session))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/projects/{projectId}/sessions/{sessionId}", projectId, sessionId(session)))
                .andExpect(status().isUnauthorized());

        // Rejected deletions leave the history exactly as it was (CA-12 analogue for chat history).
        assertThat(readSessionMessages(owner, projectId, sessionId(session)))
                .extracting(message -> message.path("body").asText()).containsExactly("a borrar");
    }

    @Test
    void sessionMessagesReportSessionAndHumanKindWithoutAiInvocation() throws Exception {
        String owner = username("kind-owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        JsonNode session = createSession(owner, projectId, viewId, "Tipos");

        MvcResult posted = mockMvc.perform(authorizedAs(owner,
                        postSessionMessageRequest(projectId, sessionId(session), "pregunta humana")))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode resource = objectMapper.readTree(posted.getResponse().getContentAsByteArray());

        assertThat(resource.path("sessionId").asText()).isEqualTo(sessionId(session).toString());
        assertThat(resource.path("kind").asText()).isEqualTo("HUMAN");

        JsonNode history = readSessionMessages(owner, projectId, sessionId(session));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("kind").asText()).isEqualTo("HUMAN");
        assertThat(history.get(0).path("sessionId").asText()).isEqualTo(sessionId(session).toString());

        // No AI message is ever written by a human post (RN-07): every stored message stays HUMAN.
        Integer aiRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM diagrammer.diagram_message WHERE kind = 'AI'", Integer.class);
        assertThat(aiRows).isZero();
    }

    @Test
    void viewerCanReadAndAskThroughSessionsButNotDelete() throws Exception {
        String owner = username("cap-owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        JsonNode session = createSession(owner, projectId, viewId, "Consulta");

        String viewer = username("cap-viewer");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);

        assertThat(listSessions(viewer, projectId, viewId)).hasSize(2);
        MvcResult posted = mockMvc.perform(authorizedAs(viewer,
                        postSessionMessageRequest(projectId, sessionId(session), "pregunta del viewer")))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode resource = objectMapper.readTree(posted.getResponse().getContentAsByteArray());
        assertThat(resource.path("kind").asText()).isEqualTo("HUMAN");

        mockMvc.perform(authorizedAs(viewer,
                        delete("/projects/{projectId}/sessions/{sessionId}", projectId, sessionId(session))))
                .andExpect(status().isForbidden());
    }

    private String username(String role) {
        // Auth usernames are capped at 64 characters; a short prefix keeps room for the UUID suffix.
        return "cs-" + role + "-" + UUID.randomUUID();
    }

    private UUID createProject(String owner) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("name", "Sessions " + UUID.randomUUID())))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode project = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return UUID.fromString(project.path("id").asText());
    }

    private UUID defaultViewId(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT dv.view_id FROM diagrammer.diagram_view dv "
                        + "JOIN diagrammer.uml_model m ON m.model_id = dv.model_id "
                        + "WHERE m.project_id = ?::uuid AND dv.is_default LIMIT 1",
                UUID.class, projectId.toString());
    }

    private JsonNode createSession(String username, UUID projectId, UUID viewId, String title) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, createSessionRequest(projectId, viewId, title)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createSessionRequest(
            UUID projectId, UUID viewId, String title) throws Exception {
        return post("/projects/{projectId}/views/{viewId}/sessions", projectId, viewId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("title", title)));
    }

    private JsonNode listSessions(String username, UUID projectId, UUID viewId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                        get("/projects/{projectId}/views/{viewId}/sessions", projectId, viewId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postSessionMessageRequest(
            UUID projectId, UUID sessionId, String body) throws Exception {
        return post("/projects/{projectId}/sessions/{sessionId}/messages", projectId, sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("body", body)));
    }

    private JsonNode postSessionMessage(String username, UUID projectId, UUID sessionId, String body)
            throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                        postSessionMessageRequest(projectId, sessionId, body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode postLegacyMessage(String username, UUID projectId, String body) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, post("/projects/{id}/messages", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("body", body)))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode readSessionMessages(String username, UUID projectId, UUID sessionId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                        get("/projects/{projectId}/sessions/{sessionId}/messages", projectId, sessionId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode readLegacyHistory(String username, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, get("/projects/{id}/messages", projectId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private UUID sessionId(JsonNode session) {
        return UUID.fromString(session.path("id").asText());
    }
}
