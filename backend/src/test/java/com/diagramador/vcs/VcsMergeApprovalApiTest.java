package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.domain.ProjectMemberRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** One approval from another editor/owner is required to merge, unless nobody else could approve. */
@SpringBootTest
@AutoConfigureMockMvc
class VcsMergeApprovalApiTest extends AuthenticatedApiTest {

    private static final String APPROVE = "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/approve";
    private static final String MERGE = "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/merge";
    private static final String CLOSE = "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}/close";
    private static final String DETAIL = "/projects/{projectId}/vcs/merge-requests/{mergeRequestId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void withAnotherEditorTheOwnerCannotMergeUntilTheEditorApproves() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String editor = username("editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        String requestId = openFeatureRequest(owner, projectId);

        JsonNode opened = getJson(owner, DETAIL, projectId, requestId);
        assertThat(opened.path("approvalRequired").asBoolean()).isTrue();
        assertThat(opened.path("approvals")).isEmpty();
        UUID mainHeadBefore = mainHead(projectId);

        JsonNode blocked = postAction(owner, MERGE, 409, projectId, requestId);
        assertThat(blocked.path("code").asText()).isEqualTo("APPROVAL_REQUIRED");
        assertThat(getJson(owner, DETAIL, projectId, requestId).path("state").asText()).isEqualTo("OPEN");
        assertThat(mainHead(projectId)).isEqualTo(mainHeadBefore);

        JsonNode approved = postAction(editor, APPROVE, 200, projectId, requestId);
        assertThat(approved.path("mergeRequestId").asText()).isEqualTo(requestId);
        assertThat(approved.path("state").asText()).isEqualTo("OPEN");
        assertThat(approved.path("approvalRequired").asBoolean()).isFalse();
        assertThat(approved.path("approvals")).hasSize(1);
        JsonNode approval = approved.path("approvals").get(0);
        assertThat(approval.path("userId").asText()).isEqualTo(userIdFor(editor).toString());
        assertThat(approval.path("displayName").asText()).isNotBlank();
        assertThat(approval.path("approvedAt").asText()).isNotBlank();

        JsonNode merged = postAction(owner, MERGE, 200, projectId, requestId);
        assertThat(merged.path("state").asText()).isEqualTo("MERGED");
        assertThat(merged.path("approvalRequired").asBoolean()).isFalse();
        assertThat(mainHead(projectId)).isNotEqualTo(mainHeadBefore);
    }

    @Test
    void theOpenerCannotApproveTheirOwnRequest() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String editor = username("editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        String requestId = openFeatureRequest(owner, projectId);

        JsonNode error = postAction(owner, APPROVE, 403, projectId, requestId);

        assertThat(error.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(error.path("message").asText()).isNotBlank();
        assertThat(approvalRows(requestId)).isZero();
        assertThat(getJson(owner, DETAIL, projectId, requestId).path("approvals")).isEmpty();
    }

    @Test
    void aViewerCannotApprove() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String viewer = username("viewer");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);
        String requestId = openFeatureRequest(owner, projectId);

        JsonNode error = postAction(viewer, APPROVE, 403, projectId, requestId);

        assertThat(error.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(approvalRows(requestId)).isZero();
    }

    @Test
    void approvingTwiceKeepsASingleApproval() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String editor = username("editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        String requestId = openFeatureRequest(owner, projectId);

        postAction(editor, APPROVE, 200, projectId, requestId);
        JsonNode second = postAction(editor, APPROVE, 200, projectId, requestId);

        assertThat(second.path("approvals")).hasSize(1);
        assertThat(approvalRows(requestId)).isEqualTo(1L);
    }

    @Test
    void aRequestThatIsNotOpenCannotBeApproved() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String editor = username("editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        String requestId = openFeatureRequest(owner, projectId);
        postAction(owner, CLOSE, 200, projectId, requestId);

        JsonNode error = postAction(editor, APPROVE, 409, projectId, requestId);

        assertThat(error.path("code").asText()).isNotBlank();
        assertThat(approvalRows(requestId)).isZero();
    }

    @Test
    void aProjectWithOnlyTheOwnerMergesWithoutApproval() throws Exception {
        String owner = username("solo");
        UUID projectId = createProject(owner);
        String requestId = openFeatureRequest(owner, projectId);

        JsonNode opened = getJson(owner, DETAIL, projectId, requestId);
        assertThat(opened.path("approvalRequired").asBoolean()).isFalse();
        assertThat(opened.path("approvals")).isEmpty();

        JsonNode merged = postAction(owner, MERGE, 200, projectId, requestId);
        assertThat(merged.path("state").asText()).isEqualTo("MERGED");
    }

    @Test
    void aViewerAloneDoesNotBlockTheMerge() throws Exception {
        String owner = username("owner-viewer");
        UUID projectId = createProject(owner);
        String viewer = username("viewer");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);
        String requestId = openFeatureRequest(owner, projectId);

        assertThat(getJson(owner, DETAIL, projectId, requestId).path("approvalRequired").asBoolean()).isFalse();
        assertThat(postAction(owner, MERGE, 200, projectId, requestId).path("state").asText())
                .isEqualTo("MERGED");
    }

    @Test
    void anApprovalFromAReviewerWhoLostTheRoleNoLongerCounts() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String first = username("editor-1");
        bearer(first);
        addMembership(first, projectId, ProjectMemberRole.EDITOR);
        String second = username("editor-2");
        bearer(second);
        addMembership(second, projectId, ProjectMemberRole.EDITOR);
        String requestId = openFeatureRequest(owner, projectId);
        postAction(first, APPROVE, 200, projectId, requestId);
        assertThat(getJson(owner, DETAIL, projectId, requestId).path("approvalRequired").asBoolean()).isFalse();

        mockMvc.perform(authorizedAs(owner, patch("/projects/{projectId}/members/{userId}", projectId,
                userIdFor(first))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("role", "VIEWER")))))
                .andExpect(status().isOk());
        UUID mainHeadBefore = mainHead(projectId);

        JsonNode detail = getJson(owner, DETAIL, projectId, requestId);
        assertThat(detail.path("approvalRequired").asBoolean()).isTrue();
        assertThat(postAction(owner, MERGE, 409, projectId, requestId).path("code").asText())
                .isEqualTo("APPROVAL_REQUIRED");
        assertThat(getJson(owner, DETAIL, projectId, requestId).path("state").asText()).isEqualTo("OPEN");
        assertThat(mainHead(projectId)).isEqualTo(mainHeadBefore);

        JsonNode approved = postAction(second, APPROVE, 200, projectId, requestId);
        assertThat(approved.path("approvalRequired").asBoolean()).isFalse();
        assertThat(postAction(owner, MERGE, 200, projectId, requestId).path("state").asText())
                .isEqualTo("MERGED");
    }

    private String openFeatureRequest(String owner, UUID projectId) throws Exception {
        getJson(owner, "/projects/{projectId}/vcs/status", projectId);
        postBody(owner, "/projects/{projectId}/vcs/branches", projectId,
                Map.of("name", "feature", "fromBranch", "main"), 201);
        postAction(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "feature");
        applyClassifier(owner, projectId, UUID.randomUUID(), "Approved element");
        postBody(owner, "/projects/{projectId}/vcs/commits", projectId, Map.of("message", "Feature element"), 201);
        postAction(owner, "/projects/{projectId}/vcs/branches/{name}/checkout", 200, projectId, "main");
        return postBody(owner, "/projects/{projectId}/vcs/merge-requests", projectId,
                Map.of("sourceBranch", "feature", "targetBranch", "main", "title", "Needs a reviewer"), 201)
                .path("mergeRequestId").asText();
    }

    private UUID mainHead(UUID projectId) {
        return jdbcTemplate.queryForObject("SELECT b.head_commit_id FROM diagrammer.vcs_branch b "
                + "JOIN diagrammer.uml_model m ON m.model_id = b.model_id "
                + "WHERE m.project_id = ?::uuid AND b.name = 'main'", UUID.class, projectId.toString());
    }

    private long approvalRows(String requestId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.vcs_merge_request_approval "
                + "WHERE merge_request_id = ?::uuid", Long.class, requestId);
    }

    private String username(String prefix) {
        return "vcs-appr-" + prefix + "-" + UUID.randomUUID();
    }

    private UUID createProject(String owner) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "Approval " + UUID.randomUUID())))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).path("id").asText());
    }

    private void applyClassifier(String owner, UUID projectId, UUID classifierId, String name) throws Exception {
        JsonNode model = getJson(owner, "/projects/{projectId}/model", projectId);
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("operationId", UUID.randomUUID());
        envelope.put("type", "classifier.create");
        envelope.put("projectId", projectId);
        envelope.put("modelId", UUID.fromString(model.path("id").asText()));
        envelope.put("baseRevision", model.path("revision").asLong());
        envelope.put("targets", List.of());
        envelope.put("preconditions", List.of());
        envelope.put("payload", Map.of("id", classifierId, "name", name, "kind", "CLASS"));
        envelope.put("origin", "MANUAL");
        envelope.put("idempotencyKey", UUID.randomUUID().toString());
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects/{projectId}/commands", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(envelope))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(read(result).path("status").asText()).isEqualTo("ACCEPTED");
    }

    private JsonNode getJson(String username, String path, Object... pathVariables) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, get(path, pathVariables)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result);
    }

    private JsonNode postAction(String username, String path, int expectedStatus, Object... pathVariables)
            throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, post(path, pathVariables)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return read(result);
    }

    private JsonNode postBody(String username, String path, UUID projectId, Map<String, Object> body,
            int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, post(path, projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return read(result);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
