package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.domain.ProjectMemberRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
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
 * CA-41/PD-04 live test (T10/L53): five users ask through the shared queue (one running + four
 * queued, no per-session model) against the REAL llama-server runtime. Skipped (not failed) when
 * the runtime is down, so offline suites stay green. This is the ODD's closing measurement: the
 * meter counts REAL tokens through the runtime's /tokenize.
 */
@SpringBootTest(properties = "diagrammer.assistant.enabled=true")
@AutoConfigureMockMvc
class FiveUsersLiveTest extends AuthenticatedApiTest {

    private static final long AWAIT_MS = 180_000;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static boolean runtimeHealthy() {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:8099/health")).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception exception) {
            return false;
        }
    }

    @Test
    void fiveUsersAllGetTheirDistinctAnswersThroughTheSharedQueue() throws Exception {
        Assumptions.assumeTrue(runtimeHealthy(),
                "The llama-server runtime is not reachable on 127.0.0.1:8099; CA-41 live test skipped.");

        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        UUID[] sessionIds = new UUID[5];
        String[] questionIds = new String[5];
        List<String> users = new ArrayList<>();

        // Five users, one session each, one question each — PD-04: one running + four queued.
        for (int index = 0; index < 5; index++) {
            String user = username("user-" + index);
            bearer(user);
            addMembership(user, projectId, ProjectMemberRole.EDITOR);
            users.add(user);
            sessionIds[index] = createSession(user, projectId, viewId, "Usuario " + index);
            questionIds[index] = postMessage(user, projectId, sessionIds[index],
                    "En una frase: ¿para qué sirve un diagrama de clases UML?");
        }

        long startedAt = System.currentTimeMillis();
        for (int index = 0; index < 5; index++) {
            mockMvc.perform(ask(users.get(index), projectId, sessionIds[index], questionIds[index]))
                    .andExpect(status().isAccepted());
        }

        // Every user gets THEIR answer: linked to their question, in their session, no mixing.
        Set<String> answerIds = new HashSet<>();
        for (int index = 0; index < 5; index++) {
            JsonNode answer = awaitAiAnswer(users.get(index), projectId, sessionIds[index], questionIds[index]);
            answerIds.add(answer.path("id").asText());
        }
        long elapsedMs = System.currentTimeMillis() - startedAt;
        assertThat(answerIds).hasSize(5);

        // The queue drained completely: no pending AI request remains in any session.
        for (int index = 0; index < 5; index++) {
            MvcResult pending = mockMvc.perform(authorizedAs(users.get(index),
                            get("/projects/{projectId}/sessions/{sessionId}/ai-request", projectId, sessionIds[index])))
                    .andReturn();
            assertThat(pending.getResponse().getStatus()).isEqualTo(404);
        }

        // Real token measurement through the runtime's /tokenize (S10 authority, T10/L53).
        JsonNode meter = meter(owner, projectId, sessionIds[0]);
        System.out.println("[T10/L53] five-user live measurement: elapsedMs=" + elapsedMs
                + ", answers=5, meterTotalUsed=" + meter.path("totalUsed").asLong()
                + ", meterHistory=" + meter.path("history").asLong()
                + ", meterPercent=" + meter.path("percent").asInt());
    }

    private String username(String role) {
        // Auth usernames are capped at 64 characters; a short prefix keeps room for the UUID suffix.
        return "live-" + role + "-" + UUID.randomUUID();
    }

    private UUID createProject(String owner) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("name", "Live " + UUID.randomUUID())))))
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

    private MockHttpServletRequestBuilder ask(String username, UUID projectId, UUID sessionId, String messageId)
            throws Exception {
        return authorizedAs(username, post("/projects/{projectId}/sessions/{sessionId}/ask", projectId, sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("messageId", messageId))));
    }

    private JsonNode meter(String username, UUID projectId, UUID sessionId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                        get("/projects/{projectId}/sessions/{sessionId}/meter", projectId, sessionId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode awaitAiAnswer(String username, UUID projectId, UUID sessionId, String questionId)
            throws Exception {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
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
            Thread.sleep(500);
        }
        throw new AssertionError("No AI answer linked to " + questionId + " appeared within " + AWAIT_MS + "ms");
    }
}
