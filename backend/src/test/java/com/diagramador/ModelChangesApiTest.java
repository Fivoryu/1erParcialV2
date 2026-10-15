package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.domain.ProjectMemberRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Contract tests for the accepted-change log (T4 of odd/tasks/chat-ia-colaborativo-web.md, L41).
 * The existing model_command registry is reused (S12): every accepted change is recoverable with
 * author, origin and revisions without any LLM involvement (CA-11); rejected commands leave no
 * change record and the revision stands still (CA-12); and accepted changes count as pending
 * derived processing until later tasks consume them (CA-19).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModelChangesApiTest extends AuthenticatedApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void acceptedChangesAreListedWithAuthorOriginAndRevisions() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID modelId = modelId(projectId);
        applyCommand(owner, projectId, modelId, 1L);

        JsonNode changes = changes(owner, projectId, null, null);
        // The registry records the project's birth too (project.create, revision 0->1).
        assertThat(changes).hasSize(2);
        JsonNode change = changes.get(0);
        assertThat(change.path("operation").asText()).isEqualTo("classifier.create");
        assertThat(change.path("origin").asText()).isEqualTo("MANUAL");
        assertThat(change.path("authorUserId").asText()).isEqualTo(userIdFor(owner).toString());
        assertThat(change.path("baseRevision").asLong()).isEqualTo(1L);
        assertThat(change.path("resultRevision").asLong()).isEqualTo(2L);
        assertThat(change.path("knowledgePending").asBoolean()).isTrue();
        assertThat(change.path("authorDisplayName").asText()).isNotBlank();
        assertThat(changes.get(1).path("operation").asText()).isEqualTo("project.create");
        assertThat(changes.get(1).path("resultRevision").asLong()).isEqualTo(1L);
    }

    @Test
    void rejectedCommandsLeaveNoChangeRecordAndTheRevisionStandsStill() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID modelId = modelId(projectId);

        // A stale base revision is always rejected.
        MvcResult rejected = mockMvc.perform(authorizedAs(owner,
                        post("/projects/{projectId}/commands", projectId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(commandEnvelope(projectId, modelId, 99L))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(rejected.getResponse().getContentAsByteArray())
                .path("status").asText()).isEqualTo("REJECTED");

        // Only the project's birth change remains: the rejected command left no row (CA-12).
        List<String> operations = new ArrayList<>();
        changes(owner, projectId, null, null).forEach(change -> operations.add(change.path("operation").asText()));
        assertThat(operations).containsExactly("project.create");
        assertThat(currentRevision(projectId)).isEqualTo(1L);
    }

    @Test
    void pendingCountCountsAcceptedChangesWithoutAnyModelInvocation() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID modelId = modelId(projectId);
        applyCommand(owner, projectId, modelId, 1L);
        applyCommand(owner, projectId, modelId, 2L);

        // The pending count includes the project's birth change: 2 commands + project.create.
        JsonNode response = pendingCount(owner, projectId);
        assertThat(response.path("pendingChanges").asLong()).isEqualTo(3L);
    }

    @Test
    void sinceRevisionReturnsOnlyNewerChangesAndLimitCapsTheWindow() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID modelId = modelId(projectId);
        applyCommand(owner, projectId, modelId, 1L);
        applyCommand(owner, projectId, modelId, 2L);

        JsonNode newer = changes(owner, projectId, 1L, null);
        assertThat(newer).hasSize(2);
        assertThat(newer.get(0).path("resultRevision").asLong()).isEqualTo(3L);
        assertThat(newer.get(1).path("resultRevision").asLong()).isEqualTo(2L);

        JsonNode capped = changes(owner, projectId, null, 1);
        assertThat(capped).hasSize(1);
        assertThat(capped.get(0).path("resultRevision").asLong()).isEqualTo(3L);
    }

    @Test
    void viewersCanReadChangesAndForeignProjectsAreNotFound() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID modelId = modelId(projectId);
        applyCommand(owner, projectId, modelId, 1L);
        String viewer = username("viewer");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);

        // Reading the change log is a conversation-level capability: it grants no apply rights.
        assertThat(changes(viewer, projectId, null, null)).hasSize(2);

        String outsider = username("outsider");
        UUID foreignProject = createProject(outsider);
        bearer(outsider);
        // A project the caller cannot see at all stays 403 (requireMember semantics, as in chat);
        // 404 is the answer for foreign ids inside an accessible project.
        mockMvc.perform(authorizedAs(outsider, get("/projects/{projectId}/model/changes", projectId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authorizedAs(viewer, get("/projects/{projectId}/model/changes", foreignProject)))
                .andExpect(status().isForbidden());
    }

    private String username(String role) {
        // Auth usernames are capped at 64 characters; a short prefix keeps room for the UUID suffix.
        return "mc-" + role + "-" + UUID.randomUUID();
    }

    private UUID createProject(String owner) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("name", "Changes " + UUID.randomUUID())))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText());
    }

    private UUID modelId(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT model_id FROM diagrammer.uml_model WHERE project_id = ?::uuid LIMIT 1",
                UUID.class, projectId.toString());
    }

    private long currentRevision(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT current_revision FROM diagrammer.uml_model WHERE project_id = ?::uuid LIMIT 1",
                Long.class, projectId.toString());
    }

    private void applyCommand(String username, UUID projectId, UUID modelId, long baseRevision) throws Exception {
        mockMvc.perform(authorizedAs(username, post("/projects/{projectId}/commands", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(commandEnvelope(projectId, modelId, baseRevision))))
                .andExpect(status().isOk());
    }

    private byte[] commandEnvelope(UUID projectId, UUID modelId, long baseRevision) throws Exception {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("operationId", UUID.randomUUID());
        envelope.put("type", "classifier.create");
        envelope.put("projectId", projectId);
        envelope.put("modelId", modelId);
        envelope.put("baseRevision", baseRevision);
        envelope.put("targets", List.of());
        envelope.put("preconditions", List.of());
        envelope.put("payload", Map.of("id", UUID.randomUUID(), "name", "Change " + UUID.randomUUID(),
                "kind", "CLASS"));
        envelope.put("origin", "MANUAL");
        envelope.put("idempotencyKey", UUID.randomUUID().toString());
        return objectMapper.writeValueAsBytes(envelope);
    }

    private JsonNode changes(String username, UUID projectId, Long sinceRevision, Integer limit) throws Exception {
        MockHttpServletRequestBuilder builder =
                authorizedAs(username, get("/projects/{projectId}/model/changes", projectId));
        if (sinceRevision != null) {
            builder.param("sinceRevision", sinceRevision.toString());
        }
        if (limit != null) {
            builder.param("limit", limit.toString());
        }
        MvcResult result = mockMvc.perform(builder)
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode pendingCount(String username, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                        get("/projects/{projectId}/model/changes/pending-count", projectId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
