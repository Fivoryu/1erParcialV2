package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Public HTTP contracts for T1 memory reads, direct writes, and explicitly requested search. */
@SpringBootTest(properties = "diagrammer.assistant.enabled=true")
@AutoConfigureMockMvc
class MemoryOperationsApiTest extends AuthenticatedApiTest {

    private static final String SEARCH_PATH = "/projects/{projectId}/views/{viewId}/memories/search";
    private static final String MEMORY_PATH = "/projects/{projectId}/views/{viewId}/memories/{memoryId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private com.diagramador.assistant.LlamaCppAssistantProvider chatAssistantProvider;

    @Test
    void readsRequireActiveMembershipAndRejectedReadsDoNotLeakStoredMemories() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner, "MemoryReadProject");
        UUID viewId = defaultViewId(projectId);
        String memoryId = extractMemory(owner, projectId, viewId, "private-search-needle", "DECISION");
        JsonNode before = memories(owner, projectId, viewId);
        UUID foreignProject = createProject(owner, "MemoryPrivateProject");
        UUID foreignView = defaultViewId(foreignProject);
        String foreignMemoryId = extractMemory(owner, foreignProject, foreignView,
                "inaccessiblemarker", "DECISION");

        String outsider = username("outsider");
        mockMvc.perform(authorizedAs(outsider, get(MEMORY_PATH, projectId, viewId, memoryId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(authorizedAs(outsider, post(SEARCH_PATH, projectId, viewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("query", "private-search-needle", "includeAccessibleProjects", true)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String inactiveTarget = username("inactive-target");
        bearer(inactiveTarget);
        addMembership(inactiveTarget, projectId, ProjectMemberRole.VIEWER);
        deactivateMembership(projectId, inactiveTarget);
        mockMvc.perform(authorizedAs(inactiveTarget, post(SEARCH_PATH, projectId, viewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("query", "private-search-needle", "includeAccessibleProjects", true)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String reader = username("reader");
        bearer(reader);
        addMembership(reader, projectId, ProjectMemberRole.VIEWER);
        addMembership(reader, foreignProject, ProjectMemberRole.VIEWER);
        deactivateMembership(foreignProject, reader);
        JsonNode crossSearch = search(reader, projectId, viewId,
                Map.of("query", "inaccessiblemarker", "includeAccessibleProjects", true));
        assertThat(crossSearch.path("results")).isEmpty();
        mockMvc.perform(authorizedAs(reader, get(MEMORY_PATH, projectId, viewId, memoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memoryId").value(memoryId));

        assertThat(memories(owner, projectId, viewId)).isEqualTo(before);
        assertThat(memories(owner, foreignProject, foreignView)).hasSize(1);
        assertThat(memories(owner, foreignProject, foreignView).get(0).path("memoryId").asText())
                .isEqualTo(foreignMemoryId);
        mockMvc.perform(authorizedAs(owner, get(MEMORY_PATH, projectId, viewId, memoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memoryId").value(memoryId))
                .andExpect(jsonPath("$.content").value("private-search-needle"));
    }

    @Test
    void searchesCurrentDiagramByDefaultAndLabelsBoundedAccessibleProjectAndUmlResults() throws Exception {
        String owner = username("owner");
        UUID currentProject = createProject(owner, "MemoryCurrentProject");
        UUID currentView = defaultViewId(currentProject);
        String currentMemoryId = extractMemory(owner, currentProject, currentView,
                "cross-project-memory-needle current", "DECISION");

        UUID otherProject = createProject(owner, "MemoryAccessibleProjectBeta");
        UUID otherView = defaultViewId(otherProject);
        String otherMemoryId = extractMemory(owner, otherProject, otherView,
                "cross-project-memory-needle external", "HYPOTHESIS");
        String classifierName = "AccessibleClassifierNeedle";
        createClassifier(owner, otherProject, classifierName);

        JsonNode defaultSearch = search(owner, currentProject, currentView,
                Map.of("query", "needle", "limit", 10));
        assertThat(defaultSearch.path("results")).hasSize(1);
        assertThat(defaultSearch.path("results").get(0).path("memoryId").asText()).isEqualTo(currentMemoryId);
        assertThat(defaultSearch.path("results").get(0).path("source").path("projectId").asText())
                .isEqualTo(currentProject.toString());

        JsonNode crossProjectSearch = search(owner, currentProject, currentView,
                Map.of("query", "needle", "includeAccessibleProjects", true, "limit", 10));
        assertThat(crossProjectSearch.path("results").findValuesAsText("memoryId"))
                .contains(currentMemoryId, otherMemoryId);
        JsonNode otherMemory = findResult(crossProjectSearch, "memoryId", otherMemoryId);
        assertThat(otherMemory.path("type").asText()).isEqualTo("MEMORY");
        assertThat(otherMemory.path("source").path("projectName").asText()).isEqualTo("MemoryAccessibleProjectBeta");
        assertThat(otherMemory.path("source").path("viewId").asText()).isEqualTo(otherView.toString());

        JsonNode umlSearch = search(owner, currentProject, currentView,
                Map.of("query", classifierName, "includeAccessibleProjects", true, "limit", 10));
        JsonNode classifier = findResult(umlSearch, "type", "UML_CLASSIFIER");
        assertThat(classifier.path("content").asText()).contains(classifierName);
        assertThat(classifier.path("source").path("projectName").asText()).isEqualTo("MemoryAccessibleProjectBeta");

        JsonNode projectSearch = search(owner, currentProject, currentView,
                Map.of("query", "MemoryAccessibleProjectBeta", "includeAccessibleProjects", true, "limit", 10));
        JsonNode project = findResult(projectSearch, "type", "PROJECT");
        assertThat(project.path("source").path("projectId").asText()).isEqualTo(otherProject.toString());
        assertThat(project.path("source").path("projectName").asText()).isEqualTo("MemoryAccessibleProjectBeta");

        JsonNode model = model(owner, otherProject);
        String diagramName = model.path("views").get(0).path("name").asText();
        JsonNode diagramSearch = search(owner, currentProject, currentView,
                Map.of("query", diagramName, "includeAccessibleProjects", true, "limit", 10));
        JsonNode diagram = findResultForProject(diagramSearch, "DIAGRAM", otherProject);
        assertThat(diagram.path("source").path("viewId").asText()).isEqualTo(otherView.toString());
        assertThat(diagram.path("source").path("viewName").asText()).isEqualTo(diagramName);

        JsonNode bounded = search(owner, currentProject, currentView,
                Map.of("query", "needle", "includeAccessibleProjects", true, "limit", 1));
        assertThat(bounded.path("results")).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void writesRequireEditorValidateBeforeMutationAndRevisionLinksAtomically() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner, "MemoryWriteProject");
        UUID viewId = defaultViewId(projectId);
        String predecessorId = extractMemory(owner, projectId, viewId, "original decision", "DECISION");
        JsonNode before = memories(owner, projectId, viewId);

        String viewer = username("viewer");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);
        mockMvc.perform(authorizedAs(viewer, post("/projects/{projectId}/views/{viewId}/memories", projectId, viewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kind", "DECISION", "content", "viewer write")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(authorizedAs(viewer, put(MEMORY_PATH, projectId, viewId, predecessorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kind", "DECISION", "content", "viewer revision")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String editor = username("editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);

        mockMvc.perform(authorizedAs(owner, post("/projects/{projectId}/views/{viewId}/memories", projectId, viewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kind", "NOT_A_MEMORY_KIND", "content", "invalid kind")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"));
        mockMvc.perform(authorizedAs(owner, post("/projects/{projectId}/views/{viewId}/memories", projectId, viewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kind", "DECISION", "content", "x".repeat(2001))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"));
        mockMvc.perform(authorizedAs(owner, post("/projects/{projectId}/views/{viewId}/memories", projectId, viewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kind", "DECISION", "content", "  ")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"));

        UUID foreignProject = createProject(owner, "MemoryForeignProject");
        UUID foreignView = defaultViewId(foreignProject);
        String foreignMemoryId = extractMemory(owner, foreignProject, foreignView, "foreign decision", "DECISION");
        mockMvc.perform(authorizedAs(owner,
                        post("/projects/{projectId}/views/{viewId}/memories", projectId, foreignView)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("kind", "DECISION", "content", "wrong view")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(authorizedAs(owner, put(MEMORY_PATH, projectId, viewId, foreignMemoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kind", "DECISION", "content", "must not replace foreign")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(authorizedAs(owner, put(MEMORY_PATH, projectId, viewId, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kind", "DECISION", "content", "missing target")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(memories(owner, projectId, viewId)).isEqualTo(before);
        assertThat(memories(owner, foreignProject, foreignView)).hasSize(1);

        MvcResult saved = mockMvc.perform(authorizedAs(editor,
                        post("/projects/{projectId}/views/{viewId}/memories", projectId, viewId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("kind", "HYPOTHESIS", "content", "editor note")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("HYPOTHESIS"))
                .andReturn();
        String editorMemoryId = objectMapper.readTree(saved.getResponse().getContentAsByteArray())
                .path("memoryId").asText();
        assertThat(memories(owner, projectId, viewId).findValuesAsText("memoryId"))
                .contains(predecessorId, editorMemoryId);

        MvcResult revised = mockMvc.perform(authorizedAs(owner,
                        put(MEMORY_PATH, projectId, viewId, predecessorId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("kind", "DECISION", "content", "revised decision")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("revised decision"))
                .andReturn();
        String replacementId = objectMapper.readTree(revised.getResponse().getContentAsByteArray())
                .path("memoryId").asText();
        JsonNode finalMemories = memories(owner, projectId, viewId);
        JsonNode predecessor = findResult(finalMemories, "memoryId", predecessorId);
        assertThat(predecessor.path("status").asText()).isEqualTo("REPLACED");
        assertThat(predecessor.path("replacedBy").asText()).isEqualTo(replacementId);
        assertThat(predecessor.path("content").asText()).isEqualTo("original decision");
        assertThat(predecessor.path("sourceMessageId").asText())
                .isEqualTo(before.get(0).path("sourceMessageId").asText());
        assertThat(findResult(finalMemories, "memoryId", replacementId).path("status").asText())
                .isEqualTo("ACTIVE");
    }

    private String username(String role) {
        return "mop-" + role + "-" + UUID.randomUUID();
    }

    private UUID createProject(String owner, String name) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name, "description", "project context for " + name)))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText());
    }

    private void deactivateMembership(UUID projectId, String username) {
        jdbcTemplate.update("UPDATE diagrammer.project_member SET status = 'REMOVED' "
                        + "WHERE project_id = ?::uuid AND user_id = ?::uuid",
                projectId.toString(), userIdFor(username).toString());
    }

    private UUID defaultViewId(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT dv.view_id FROM diagrammer.diagram_view dv "
                        + "JOIN diagrammer.uml_model m ON m.model_id = dv.model_id "
                        + "WHERE m.project_id = ?::uuid AND dv.is_default LIMIT 1",
                UUID.class, projectId.toString());
    }

    private String extractMemory(String owner, UUID projectId, UUID viewId, String content, String kind)
            throws Exception {
        MvcResult session = mockMvc.perform(authorizedAs(owner,
                        post("/projects/{projectId}/views/{viewId}/sessions", projectId, viewId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("title", "Memory operation source")))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID sessionId = UUID.fromString(objectMapper.readTree(session.getResponse().getContentAsByteArray())
                .path("id").asText());
        MvcResult message = mockMvc.perform(authorizedAs(owner,
                        post("/projects/{projectId}/sessions/{sessionId}/messages", projectId, sessionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("body", content)))))
                .andExpect(status().isCreated())
                .andReturn();
        String messageId = objectMapper.readTree(message.getResponse().getContentAsByteArray()).path("id").asText();
        String providerResponse = json(Map.of("memories", List.of(Map.of(
                "content", content, "kind", kind, "sourceMessageId", messageId))));
        when(chatAssistantProvider.chat(anyList())).thenReturn(providerResponse);
        mockMvc.perform(authorizedAs(owner,
                        post("/projects/{projectId}/views/{viewId}/memories/extract", projectId, viewId)))
                .andExpect(status().isOk());
        JsonNode memories = memories(owner, projectId, viewId);
        return memories.get(memories.size() - 1).path("memoryId").asText();
    }

    private void createClassifier(String owner, UUID projectId, String name) throws Exception {
        JsonNode model = model(owner, projectId);
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("operationId", UUID.randomUUID());
        envelope.put("type", "classifier.create");
        envelope.put("projectId", projectId);
        envelope.put("modelId", model.path("id").asText());
        envelope.put("baseRevision", model.path("revision").asLong());
        envelope.put("targets", List.of());
        envelope.put("preconditions", List.of());
        envelope.put("payload", Map.of("id", UUID.randomUUID(), "name", name, "kind", "CLASS"));
        envelope.put("origin", "MANUAL");
        envelope.put("idempotencyKey", UUID.randomUUID());
        mockMvc.perform(authorizedAs(owner,
                        post("/projects/{projectId}/commands", projectId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(envelope))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    private JsonNode model(String owner, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, get("/projects/{projectId}/model", projectId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode memories(String owner, UUID projectId, UUID viewId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner,
                        get("/projects/{projectId}/views/{viewId}/memories", projectId, viewId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode search(String owner, UUID projectId, UUID viewId, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post(SEARCH_PATH, projectId, viewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode findResult(JsonNode results, String field, String value) {
        if (results.isArray()) {
            for (JsonNode result : results) {
                if (value.equals(result.path(field).asText())) {
                    return result;
                }
            }
        } else if (results.path("results").isArray()) {
            return findResult(results.path("results"), field, value);
        }
        throw new AssertionError("No result has " + field + " = " + value + ": " + results);
    }

    private JsonNode findResultForProject(JsonNode results, String type, UUID projectId) {
        for (JsonNode result : results.path("results")) {
            if (type.equals(result.path("type").asText())
                    && projectId.toString().equals(result.path("source").path("projectId").asText())) {
                return result;
            }
        }
        throw new AssertionError("No " + type + " result for project " + projectId + ": " + results);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
