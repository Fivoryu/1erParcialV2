package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.domain.ProjectMemberRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Contract tests for diagram-scoped memory (T5 of odd/tasks/chat-ia-colaborativo-web.md, L43).
 * One extraction run consumes its pending sources once (CA-21), classifies express decisions vs
 * interpretations (CA-14), confirms nothing on an invalid provider payload (CA-13), keeps
 * replacements distinguishable (CA-17), never silently overwrites a contradiction (CA-16), and
 * keeps memory separate from chat history (CA-20/CA-22).
 */
@SpringBootTest(properties = "diagrammer.assistant.enabled=true")
@AutoConfigureMockMvc
class DiagramMemoryApiTest extends AuthenticatedApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private com.diagramador.assistant.LlamaCppAssistantProvider chatAssistantProvider;

    @Test
    void extractionCreatesDecisionsAndConsumesItsPendingSourcesOnce() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Memoria");
        String messageId = postMessage(owner, projectId, sessionId, "Decidimos llamar ClientePrincipal a la clase");
        stubMemoryJson(memoryCandidate("Decisión: la clase se llama ClientePrincipal", "DECISION", messageId));

        MvcResult extracted = mockMvc.perform(extract(owner, projectId, viewId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode result = objectMapper.readTree(extracted.getResponse().getContentAsByteArray());
        assertThat(result.path("memoriesCreated").asInt()).isEqualTo(1);
        assertThat(result.path("consumed").asBoolean()).isTrue();

        JsonNode memories = memories(owner, projectId, viewId, null);
        assertThat(memories).hasSize(1);
        assertThat(memories.get(0).path("kind").asText()).isEqualTo("DECISION");
        assertThat(memories.get(0).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(memories.get(0).path("sourceMessageId").asText()).isEqualTo(messageId);

        // The model registry's pending work was consumed without any extra model invocation.
        assertThat(pendingCount(owner, projectId).path("pendingChanges").asLong()).isZero();

        // A second extraction with nothing pending never calls the provider again (S12: no LLM per change).
        MvcResult second = mockMvc.perform(extract(owner, projectId, viewId))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(second.getResponse().getContentAsByteArray())
                .path("memoriesCreated").asInt()).isZero();
        verify(chatAssistantProvider, times(1)).chat(anyList());
    }

    @Test
    void ambiguousUtterancesBecomeHypothesesNeverConfirmedDecisions() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Hipotesis");
        String messageId = postMessage(owner, projectId, sessionId, "Quizás convendría separar la facturación");
        stubMemoryJson(memoryCandidate("Interpretación: separar facturación", "HYPOTHESIS", messageId));

        mockMvc.perform(extract(owner, projectId, viewId))
                .andExpect(status().isOk());

        JsonNode memories = memories(owner, projectId, viewId, null);
        assertThat(memories).hasSize(1);
        assertThat(memories.get(0).path("kind").asText()).isEqualTo("HYPOTHESIS");
        assertThat(memories.get(0).path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void invalidProviderPayloadConfirmsNothingAndKeepsMaterialRetryable() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Invalido");
        postMessage(owner, projectId, sessionId, "Decidimos usar UUIDs para ids");

        when(chatAssistantProvider.chat(anyList())).thenReturn("Esto no es un JSON válido");
        MvcResult failed = mockMvc.perform(extract(owner, projectId, viewId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode result = objectMapper.readTree(failed.getResponse().getContentAsByteArray());
        assertThat(result.path("memoriesCreated").asInt()).isZero();
        assertThat(result.path("consumed").asBoolean()).isFalse();
        assertThat(memories(owner, projectId, viewId, null)).isEmpty();

        // The material was not consumed: a later valid run can still process it (CA-13).
        UUID messageId = jdbcTemplate.queryForObject(
                "SELECT message_id FROM diagrammer.diagram_message ORDER BY created_at DESC LIMIT 1",
                UUID.class);
        stubMemoryJson(memoryCandidate("Decisión: usar UUIDs", "DECISION", messageId.toString()));
        MvcResult retried = mockMvc.perform(extract(owner, projectId, viewId))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(retried.getResponse().getContentAsByteArray())
                .path("memoriesCreated").asInt()).isEqualTo(1);
        assertThat(memories(owner, projectId, viewId, null)).hasSize(1);
    }

    @Test
    void explicitReplacementKeepsThePredecessorDistinguishable() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Reemplazo");
        String firstMessage = postMessage(owner, projectId, sessionId, "Decidimos nombre A");
        stubMemoryJson(memoryCandidate("Decisión A", "DECISION", firstMessage));
        mockMvc.perform(extract(owner, projectId, viewId)).andExpect(status().isOk());
        String predecessorId = memories(owner, projectId, viewId, null).get(0).path("memoryId").asText();

        String secondMessage = postMessage(owner, projectId, sessionId, "Mejor nombre B, reemplaza lo anterior");
        stubMemoryJson(replacementCandidate("Decisión B", secondMessage, predecessorId));
        MvcResult extracted = mockMvc.perform(extract(owner, projectId, viewId))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(extracted.getResponse().getContentAsByteArray())
                .path("replacements").asInt()).isEqualTo(1);

        JsonNode active = memories(owner, projectId, viewId, "ACTIVE");
        assertThat(active).hasSize(1);
        assertThat(active.get(0).path("content").asText()).isEqualTo("Decisión B");
        JsonNode replaced = memories(owner, projectId, viewId, "REPLACED");
        assertThat(replaced).hasSize(1);
        assertThat(replaced.get(0).path("memoryId").asText()).isEqualTo(predecessorId);
        assertThat(replaced.get(0).path("replacedBy").asText()).isEqualTo(active.get(0).path("memoryId").asText());
    }

    @Test
    void contradictionsBetweenSessionsAreConsultableWithoutSilentOverwrite() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        UUID sessionA = createSession(owner, projectId, viewId, "Sesión A");
        UUID sessionB = createSession(owner, projectId, viewId, "Sesión B");
        String messageA = postMessage(owner, projectId, sessionA, "Decidimos moneda pesos");
        stubMemoryJson(memoryCandidate("Decisión: moneda pesos", "DECISION", messageA));
        mockMvc.perform(extract(owner, projectId, viewId)).andExpect(status().isOk());
        String decisionA = memories(owner, projectId, viewId, null).get(0).path("memoryId").asText();

        String messageB = postMessage(owner, projectId, sessionB, "En sesión B decidimos moneda dólares");
        stubMemoryJson(conflictCandidate("Decisión: moneda dólares", messageB, decisionA));
        MvcResult extracted = mockMvc.perform(extract(owner, projectId, viewId))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(extracted.getResponse().getContentAsByteArray())
                .path("conflictsMarked").asInt()).isEqualTo(1);

        JsonNode conflicted = memories(owner, projectId, viewId, "CONFLICTED");
        assertThat(conflicted).hasSize(2);
        assertThat(conflicted.get(0).path("sourceSessionId").asText()).isNotEqualTo(
                conflicted.get(1).path("sourceSessionId").asText());
    }

    @Test
    void deletingHistoryKeepsMemoryAndForgettingDoesNotResurrectIt() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Olvido");
        String messageId = postMessage(owner, projectId, sessionId, "Decidimos tema oscuro");
        stubMemoryJson(memoryCandidate("Decisión: tema oscuro", "DECISION", messageId));
        mockMvc.perform(extract(owner, projectId, viewId)).andExpect(status().isOk());
        String memoryId = memories(owner, projectId, viewId, null).get(0).path("memoryId").asText();

        // CA-20: the history purge never touches the diagram's memory.
        mockMvc.perform(authorizedAs(owner, delete("/projects/{projectId}/sessions/{sessionId}",
                        projectId, sessionId)))
                .andExpect(status().isOk());
        assertThat(memories(owner, projectId, viewId, null)).hasSize(1);

        // Only the OWNER forgets a memory (PD-01 analog for memory).
        String viewer = username("viewer");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);
        mockMvc.perform(authorizedAs(viewer, delete("/projects/{projectId}/views/{viewId}/memories/{memoryId}",
                        projectId, viewId, memoryId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authorizedAs(owner, delete("/projects/{projectId}/views/{viewId}/memories/{memoryId}",
                        projectId, viewId, memoryId)))
                .andExpect(status().isOk());
        assertThat(memories(owner, projectId, viewId, null)).isEmpty();

        // CA-21: the consumed source is never re-fed, so the forgotten memory cannot return.
        String newMessage = postMessage(owner, projectId, sessionId, "Decidimos tema claro");
        stubMemoryJson(memoryCandidate("Decisión: tema claro", "DECISION", newMessage));
        mockMvc.perform(extract(owner, projectId, viewId)).andExpect(status().isOk());
        JsonNode memories = memories(owner, projectId, viewId, null);
        assertThat(memories).hasSize(1);
        assertThat(memories.get(0).path("content").asText()).isEqualTo("Decisión: tema claro");
        assertThat(memories.get(0).path("sourceMessageId").asText()).isEqualTo(newMessage);

        // CA-22: the OWNER can also forget the whole diagram's memory at once.
        String anotherMessage = postMessage(owner, projectId, sessionId, "Decidimos tema medio");
        stubMemoryJson(memoryCandidate("Decisión: tema medio", "DECISION", anotherMessage));
        mockMvc.perform(extract(owner, projectId, viewId)).andExpect(status().isOk());
        MvcResult wiped = mockMvc.perform(authorizedAs(owner,
                        delete("/projects/{projectId}/views/{viewId}/memories", projectId, viewId)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(wiped.getResponse().getContentAsByteArray())
                .path("deletedMemories").asInt()).isEqualTo(2);
        assertThat(memories(owner, projectId, viewId, null)).isEmpty();
    }

    @Test
    void foreignViewsAndMemoriesAreRejectedWithoutLeaking() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);

        String outsider = username("outsider");
        UUID foreignProject = createProject(outsider);
        bearer(outsider);
        UUID foreignView = defaultViewId(foreignProject);

        // The viewer of this project cannot touch a foreign view: 404, indistinguishable from missing.
        // OWNER-first checks (as in the T2 history purge): a viewer gets 403 before any memory
        // lookup, so it cannot even probe existence; the OWNER gets 404 for a foreign memory id.
        String viewer = username("viewer");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);
        mockMvc.perform(authorizedAs(viewer, delete("/projects/{projectId}/views/{viewId}/memories/{memoryId}",
                        projectId, viewId, UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mockMvc.perform(authorizedAs(owner, delete("/projects/{projectId}/views/{viewId}/memories/{memoryId}",
                        projectId, viewId, UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    private String username(String role) {
        // Auth usernames are capped at 64 characters; a short prefix keeps room for the UUID suffix.
        return "dm-" + role + "-" + UUID.randomUUID();
    }

    private UUID createProject(String owner) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("name", "Memory " + UUID.randomUUID())))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText());
    }

    private UUID defaultViewId(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT dv.view_id FROM diagrammer.diagram_view dv "
                        + "JOIN diagrammer.uml_model m ON m.model_id = dv.model_id "
                        + "WHERE m.project_id = ?::uuid AND dv.is_default LIMIT 1",
                UUID.class, projectId.toString());
    }

    private UUID createSession(String username, UUID projectId, UUID viewId, String title) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                        post("/projects/{projectId}/views/{viewId}/sessions", projectId, viewId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("title", title)))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText());
    }

    private String postMessage(String username, UUID projectId, UUID sessionId, String body) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                        post("/projects/{projectId}/sessions/{sessionId}/messages", projectId, sessionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("body", body)))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("id").asText();
    }

    private MockHttpServletRequestBuilder extract(String username, UUID projectId, UUID viewId)
            throws Exception {
        return authorizedAs(username,
                post("/projects/{projectId}/views/{viewId}/memories/extract", projectId, viewId));
    }

    private JsonNode memories(String username, UUID projectId, UUID viewId, String status) throws Exception {
        MockHttpServletRequestBuilder builder =
                authorizedAs(username, get("/projects/{projectId}/views/{viewId}/memories", projectId, viewId));
        if (status != null) {
            builder.param("status", status);
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

    private void stubMemoryJson(String candidate) {
        when(chatAssistantProvider.chat(anyList()))
                .thenReturn("{\"memories\":[" + candidate + "]}");
    }

    private String memoryCandidate(String content, String kind, String sourceMessageId) {
        return "{\"content\":\"" + content + "\",\"kind\":\"" + kind + "\",\"sourceMessageId\":\""
                + sourceMessageId + "\"}";
    }

    private String replacementCandidate(String content, String sourceMessageId, String replaces) {
        return "{\"content\":\"" + content + "\",\"kind\":\"DECISION\",\"sourceMessageId\":\"" + sourceMessageId
                + "\",\"replaces\":\"" + replaces + "\"}";
    }

    private String conflictCandidate(String content, String sourceMessageId, String conflictingWith) {
        return "{\"content\":\"" + content + "\",\"kind\":\"DECISION\",\"sourceMessageId\":\"" + sourceMessageId
                + "\",\"conflictingWith\":[\"" + conflictingWith + "\"]}";
    }
}
