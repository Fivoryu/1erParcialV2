package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.assistant.LlamaCppAssistantProvider;
import com.diagramador.context.TokenCounter;
import com.diagramador.domain.ProjectMemberRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * Contract tests for the context budget (T6 of odd/tasks/chat-ia-colaborativo-web.md, L45, PD-03).
 * The meter reports C/I/R/P plus the components, reserves the response before the prompt budget,
 * counts only this session's history (CA-27), triggers compaction exactly at ceil(0.8*P) (CA-24),
 * preserves the decision/hypothesis distinction while keeping sources recoverable (CA-25), and
 * honestly refuses an ask that still does not fit instead of silently truncating (CA-26).
 */
@SpringBootTest(properties = "diagrammer.assistant.enabled=true")
@AutoConfigureMockMvc
class ContextMeterApiTest extends AuthenticatedApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private LlamaCppAssistantProvider chatAssistantProvider;

    // Deterministic counting (tokens == characters) makes the thresholds exact.
    @MockitoBean
    private TokenCounter tokenCounter;

    // Deterministic counting (tokens == characters, null-safe for unstubbed provider calls)
    // makes the thresholds exact.
    @BeforeEach
    void stubDeterministicCounting() {
        when(tokenCounter.count(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return text == null ? 0 : text.length();
        });
        // The meter counts whatever system prompt the provider declares; the mock supplies one.
        when(chatAssistantProvider.systemPrompt()).thenReturn("Instrucciones del sistema del chat.");
    }

    @Test
    void meterReportsBudgetsAndNeverPromisesZeroForANewSession() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID sessionId = createSession(owner, projectId, defaultViewId(projectId), "Medidor");

        JsonNode meter = meter(owner, projectId, sessionId);
        assertThat(meter.path("contextWindow").asLong()).isEqualTo(4096L);
        assertThat(meter.path("reservedForResponse").asLong()).isEqualTo(512L);
        assertThat(meter.path("promptBudget").asLong()).isEqualTo(3584L);
        // CA-24: the threshold is ceil(0.8*P), never 80% of C.
        assertThat(meter.path("promptThreshold").asLong())
                .isEqualTo((meter.path("promptBudget").asLong() * 4 + 4) / 5);
        // CA-27: a brand-new session has no history, but instructions and diagram data still count.
        assertThat(meter.path("history").asLong()).isZero();
        assertThat(meter.path("instructions").asLong()).isPositive();
        assertThat(meter.path("diagramData").asLong()).isPositive();
        assertThat(meter.path("totalUsed").asLong()).isPositive();
        assertThat(meter.path("compactionPending").asBoolean()).isFalse();
    }

    @Test
    void compactionIsFlaggedExactlyAtTheEightyPercentThresholdOfP() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID sessionId = createSession(owner, projectId, defaultViewId(projectId), "Umbral");

        long t0 = meter(owner, projectId, sessionId).path("totalUsed").asLong();
        long threshold = meter(owner, projectId, sessionId).path("promptThreshold").asLong();
        // CA-24: exactly one token below the threshold does not trigger; at/above it does.
        postExactFill(owner, projectId, sessionId, threshold - t0 - 1);
        assertThat(meter(owner, projectId, sessionId).path("totalUsed").asLong()).isEqualTo(threshold - 1);
        assertThat(meter(owner, projectId, sessionId).path("compactionPending").asBoolean()).isFalse();

        postExactFill(owner, projectId, sessionId, 1);
        assertThat(meter(owner, projectId, sessionId).path("totalUsed").asLong()).isEqualTo(threshold);
        assertThat(meter(owner, projectId, sessionId).path("compactionPending").asBoolean()).isTrue();
    }

    @Test
    void compactionPreservesDistinctionAndKeepsSourcesRecoverable() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID sessionId = createSession(owner, projectId, defaultViewId(projectId), "Compacta");
        fillPastThreshold(owner, projectId, sessionId);
        MvcResult beforeCompaction = mockMvc.perform(authorizedAs(owner,
                        get("/projects/{projectId}/sessions/{sessionId}/messages", projectId, sessionId)))
                .andExpect(status().isOk())
                .andReturn();
        int messageCountBeforeCompaction =
                objectMapper.readTree(beforeCompaction.getResponse().getContentAsByteArray()).size();

        when(chatAssistantProvider.chat(anyList())).thenReturn("{\"summary\":\"Resumen del tramo\","
                + "\"decisions\":[\"se eligió X\"],\"hypotheses\":[\"quizás Y\"],\"pending\":[\"falta Z\"]}");
        MvcResult compacted = mockMvc.perform(compact(owner, projectId, sessionId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode result = objectMapper.readTree(compacted.getResponse().getContentAsByteArray());
        assertThat(result.path("compacted").asBoolean()).isTrue();

        // The recount stays under the threshold and the history component shrank.
        JsonNode meter = meter(owner, projectId, sessionId);
        assertThat(meter.path("totalUsed").asLong()).isLessThan(meter.path("promptThreshold").asLong());
        assertThat(meter.path("history").asLong()).isPositive();

        // CA-25: the sources were never deleted.
        MvcResult history = mockMvc.perform(authorizedAs(owner,
                        get("/projects/{projectId}/sessions/{sessionId}/messages", projectId, sessionId)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(history.getResponse().getContentAsByteArray()).size())
                .isEqualTo(messageCountBeforeCompaction);
    }

    @Test
    void askRefusesHonestlyWhenTheContextStillDoesNotFitAfterCompaction() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID sessionId = createSession(owner, projectId, defaultViewId(projectId), "Overflow");
        long t0 = meter(owner, projectId, sessionId).path("totalUsed").asLong();
        long promptBudget = 3584L;
        postExactFill(owner, projectId, sessionId, promptBudget - t0 + 100);

        // The compaction attempt fails (invalid payload), so the ask must refuse, not truncate.
        when(chatAssistantProvider.chat(anyList())).thenReturn("no es un json");
        String questionId = postMessage(owner, projectId, sessionId, "pregunta");
        MvcResult asked = mockMvc.perform(ask(owner, projectId, sessionId, questionId))
                .andExpect(status().isPayloadTooLarge())
                .andReturn();
        assertThat(objectMapper.readTree(asked.getResponse().getContentAsByteArray())
                .path("code").asText()).isEqualTo("CONTEXT_OVERFLOW");
    }

    @Test
    void askIntegratesMemoriesAndTheCompactedSummaryInItsContext() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        UUID sessionA = createSession(owner, projectId, viewId, "A");
        UUID sessionB = createSession(owner, projectId, viewId, "B");

        // A memory exists for the diagram (extracted from session A's message).
        String memorySource = postMessage(owner, projectId, sessionA, "Decidimos nombrar ClientePrincipal");
        when(chatAssistantProvider.chat(anyList())).thenReturn(
                "{\"memories\":[{\"content\":\"Decisión: ClientePrincipal\",\"kind\":\"DECISION\","
                        + "\"sourceMessageId\":\"" + memorySource + "\"}]}");
        mockMvc.perform(authorizedAs(owner,
                        post("/projects/{projectId}/views/{viewId}/memories/extract", projectId, viewId)))
                .andExpect(status().isOk());

        // Fill session B past the threshold so the ask compacts first, then answers.
        long t0 = meter(owner, projectId, sessionB).path("totalUsed").asLong();
        long threshold = 2868L;
        postExactFill(owner, projectId, sessionB, threshold - t0);
        String questionId = postMessage(owner, projectId, sessionB, "pregunta de la sesión B");

        when(chatAssistantProvider.chat(anyList()))
                .thenReturn("{\"summary\":\"Resumen del tramo previo\",\"decisions\":[],\"hypotheses\":[],\"pending\":[]}",
                        "respuesta final");
        mockMvc.perform(ask(owner, projectId, sessionB, questionId))
                .andExpect(status().isAccepted());
        awaitAiAnswer(owner, projectId, sessionB, questionId);

        // Provider calls in order: [0] memory extraction, [1] compaction, [2] the answer itself.
        ArgumentCaptor<List<com.diagramador.assistant.ChatAssistantProvider.Turn>> calls = ArgumentCaptor.captor();
        verify(chatAssistantProvider, timeout(5_000).times(3)).chat(calls.capture());
        String answerConversation = calls.getAllValues().get(2).stream()
                .map(com.diagramador.assistant.ChatAssistantProvider.Turn::content)
                .reduce("", (left, right) -> left + "\n" + right);
        // The answer sees the compacted summary and the diagram's memories (CA-15)...
        assertThat(answerConversation).contains("Resumen del tramo previo");
        assertThat(answerConversation).contains("Decisión: ClientePrincipal");
        // ...but never another session's raw messages.
        assertThat(answerConversation).doesNotContain("Decidimos nombrar ClientePrincipal");
    }

    @Test
    void foreignSessionsAreNotFoundForTheMeter() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID sessionId = createSession(owner, projectId, defaultViewId(projectId), "Propia");
        String outsider = username("outsider");
        UUID foreignProject = createProject(outsider);
        bearer(outsider);
        UUID foreignSession = createSession(outsider, foreignProject, defaultViewId(foreignProject), "Ajena");

        mockMvc.perform(authorizedAs(outsider, get("/projects/{projectId}/sessions/{sessionId}/meter",
                        projectId, sessionId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authorizedAs(owner, get("/projects/{projectId}/sessions/{sessionId}/meter",
                        projectId, foreignSession)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingHistoryAlsoDropsTheCompactedSummary() throws Exception {
        // CA-20/L54: the compacted summary belongs to the session's history; purging the history
        // must drop it too, or the old content keeps flowing into the context.
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID sessionId = createSession(owner, projectId, defaultViewId(projectId), "Compacta y borra");
        fillPastThreshold(owner, projectId, sessionId);
        when(chatAssistantProvider.chat(anyList())).thenReturn(
                "{\"summary\":\"Resumen viejo\",\"decisions\":[],\"hypotheses\":[],\"pending\":[]}");
        mockMvc.perform(compact(owner, projectId, sessionId))
                .andExpect(status().isOk());
        assertThat(meter(owner, projectId, sessionId).path("history").asLong()).isPositive();

        mockMvc.perform(authorizedAs(owner, delete("/projects/{projectId}/sessions/{sessionId}",
                        projectId, sessionId)))
                .andExpect(status().isOk());

        // The summary is gone: the meter's history is back to zero.
        assertThat(meter(owner, projectId, sessionId).path("history").asLong()).isZero();
    }

    private String username(String role) {
        // Auth usernames are capped at 64 characters; a short prefix keeps room for the UUID suffix.
        return "cm-" + role + "-" + UUID.randomUUID();
    }

    private UUID createProject(String owner) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("name", "Meter " + UUID.randomUUID())))))
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

    private JsonNode meter(String username, UUID projectId, UUID sessionId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                        get("/projects/{projectId}/sessions/{sessionId}/meter", projectId, sessionId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private MockHttpServletRequestBuilder compact(String username, UUID projectId, UUID sessionId)
            throws Exception {
        return authorizedAs(username,
                post("/projects/{projectId}/sessions/{sessionId}/compact", projectId, sessionId));
    }

    private MockHttpServletRequestBuilder ask(String username, UUID projectId, UUID sessionId, String messageId)
            throws Exception {
        return authorizedAs(username, post("/projects/{projectId}/sessions/{sessionId}/ask", projectId, sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("messageId", messageId))));
    }

    /** Posts filler messages (≤2000 chars each: the server cap) totaling exactly totalChars. */
    private void postExactFill(String owner, UUID projectId, UUID sessionId, long totalChars) throws Exception {
        long remaining = totalChars;
        while (remaining > 0) {
            int chunk = (int) Math.min(2000, remaining);
            postMessage(owner, projectId, sessionId, "w".repeat(chunk));
            remaining -= chunk;
        }
    }

    private void fillPastThreshold(String owner, UUID projectId, UUID sessionId) throws Exception {
        JsonNode before = meter(owner, projectId, sessionId);
        long t0 = before.path("totalUsed").asLong();
        long threshold = before.path("promptThreshold").asLong();
        // Non-blank filler (sanitizeBody would strip plain-space padding), sized from the live meter.
        int messages = (int) Math.max(1, (threshold + 1 - t0) / 402 + 1);
        for (int index = 0; index < messages; index++) {
            String body = "m" + index + "w".repeat(400);
            postMessage(owner, projectId, sessionId, body);
        }
        assertThat(meter(owner, projectId, sessionId).path("compactionPending").asBoolean()).isTrue();
    }

    private JsonNode awaitAiAnswer(String username, UUID projectId, UUID sessionId, String questionId)
            throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            MvcResult result = mockMvc.perform(authorizedAs(username,
                            get("/projects/{projectId}/sessions/{sessionId}/messages", projectId, sessionId)))
                    .andExpect(status().isOk())
                    .andReturn();
            for (JsonNode message : objectMapper.readTree(result.getResponse().getContentAsByteArray())) {
                if ("AI".equals(message.path("kind").asText())
                        && questionId.equals(message.path("replyTo").asText(null))) {
                    return message;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No AI answer linked to " + questionId + " appeared within 30s");
    }
}
