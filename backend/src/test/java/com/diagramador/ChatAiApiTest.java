package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.assistant.ChatAssistantProvider;
import com.diagramador.assistant.LlamaCppAssistantProvider;
import com.diagramador.collaboration.ProjectEvent;
import com.diagramador.collaboration.ProjectEventBus;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract tests for AI asks over diagram-scoped sessions (T3 of odd/tasks/chat-ia-colaborativo-web.md).
 * Contracts are fixed by L38 and PD-04: one shared inference slot with four queued asks and
 * explicit rejection, one pending request per session, answers persisted as AI messages linked to
 * their question, real states delivered over the project event channel, viewer-visible asking, and
 * a provider context that contains only the chosen diagram and the current session's history.
 */
@SpringBootTest(properties = "diagrammer.assistant.enabled=true")
@AutoConfigureMockMvc
class ChatAiApiTest extends AuthenticatedApiTest {

    private static final long AWAIT_MS = 30_000;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UmlModelRepository modelRepository;

    @Autowired
    private ClassifierRepository classifierRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // Mocked on the concrete type: the single provider bean implements both the plan-JSON
    // contract (AssistantPlanService) and the chat contract (T3), so an interface mock would
    // leave the plan flow without an AiAssistantProvider.
    @MockitoBean
    private LlamaCppAssistantProvider chatAssistantProvider;

    @MockitoSpyBean
    private ProjectEventBus eventBus;

    @Test
    void answerIsPersistedLinkedToItsQuestionAndBroadcast() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "IA");
        String questionId = postMessage(owner, projectId, sessionId, "¿Qué clases tiene el diagrama?");
        when(chatAssistantProvider.chat(anyList())).thenReturn("El diagrama tiene las clases del contexto.");

        mockMvc.perform(ask(owner, projectId, sessionId, questionId))
                .andExpect(status().isAccepted());

        JsonNode answer = awaitAiAnswer(owner, projectId, sessionId, questionId);
        assertThat(answer.path("kind").asText()).isEqualTo("AI");
        assertThat(answer.path("replyTo").asText()).isEqualTo(questionId);
        assertThat(answer.path("authorUserId").isNull()).isTrue();
        assertThat(answer.path("authorDisplayName").asText()).isEqualTo("Asistente");
        assertThat(answer.path("body").asText()).isEqualTo("El diagrama tiene las clases del contexto.");

        ArgumentCaptor<ProjectEvent> events = ArgumentCaptor.forClass(ProjectEvent.class);
        verify(eventBus, timeout(5_000).atLeastOnce()).publish(events.capture());
        assertThat(events.getAllValues().stream()
                .filter(event -> event.projectId().equals(projectId))
                .anyMatch(event -> "message.posted".equals(event.type())
                        && sessionId.equals(event.payload().get("sessionId"))
                        && "AI".equals(event.payload().get("kind")))).isTrue();
        assertThat(events.getAllValues().stream()
                .filter(event -> event.projectId().equals(projectId))
                .anyMatch(event -> "chat.ai.status".equals(event.type())
                        && sessionId.equals(event.payload().get("sessionId"))
                        && "COMPLETED".equals(event.payload().get("state")))).isTrue();
    }

    @Test
    void aSessionAcceptsOnlyOnePendingRequestAtATime() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Dedup");
        CountDownLatch release = new CountDownLatch(1);
        when(chatAssistantProvider.chat(anyList())).thenAnswer(invocation -> {
            release.await(AWAIT_MS, TimeUnit.MILLISECONDS);
            return "primera respuesta";
        });
        String firstQuestion = postMessage(owner, projectId, sessionId, "primera");

        mockMvc.perform(ask(owner, projectId, sessionId, firstQuestion))
                .andExpect(status().isAccepted());
        String secondQuestion = postMessage(owner, projectId, sessionId, "segunda");
        MvcResult rejected = mockMvc.perform(ask(owner, projectId, sessionId, secondQuestion))
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(errorCode(rejected)).isEqualTo("AI_REQUEST_IN_FLIGHT");

        release.countDown();
        awaitAiAnswer(owner, projectId, sessionId, firstQuestion);
        // The worker clears the pending registry after the answer is persisted; wait for it or
        // the third ask could race into AI_REQUEST_IN_FLIGHT.
        awaitNoPending(owner, projectId, sessionId);

        // COMPLETED clears the pending request: a later ask in the same session works (L38).
        String thirdQuestion = postMessage(owner, projectId, sessionId, "tercera");
        when(chatAssistantProvider.chat(anyList())).thenReturn("segunda respuesta");
        mockMvc.perform(ask(owner, projectId, sessionId, thirdQuestion))
                .andExpect(status().isAccepted());
        awaitAiAnswer(owner, projectId, sessionId, thirdQuestion);
    }

    @Test
    void theGlobalQueueAdmitsOneRunningAndFourQueuedAndRejectsTheRest() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        CountDownLatch release = new CountDownLatch(1);
        when(chatAssistantProvider.chat(anyList())).thenAnswer(invocation -> {
            release.await(AWAIT_MS, TimeUnit.MILLISECONDS);
            return "respuesta " + UUID.randomUUID();
        });
        List<UUID> sessionIds = new ArrayList<>();
        List<String> questionIds = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            UUID sessionId = createSession(owner, projectId, viewId, "Cola " + index);
            sessionIds.add(sessionId);
            questionIds.add(postMessage(owner, projectId, sessionId, "pregunta " + index));
        }

        // The first ask occupies the single inference slot; the next four fill the queue (PD-04).
        for (int index = 0; index < 5; index++) {
            mockMvc.perform(ask(owner, projectId, sessionIds.get(index), questionIds.get(index)))
                    .andExpect(status().isAccepted());
        }
        MvcResult rejected = mockMvc.perform(ask(owner, projectId, sessionIds.get(5), questionIds.get(5)))
                .andExpect(status().isTooManyRequests())
                .andReturn();
        assertThat(errorCode(rejected)).isEqualTo("AI_QUEUE_FULL");

        release.countDown();
        for (int index = 0; index < 5; index++) {
            awaitAiAnswer(owner, projectId, sessionIds.get(index), questionIds.get(index));
        }
        // Each result belongs to its own session; the rejected ask never produced one (CA-05).
        assertThat(messages(owner, projectId, sessionIds.get(5))).hasSize(1);
    }

    @Test
    void cancellingAQueuedRequestDropsItWithoutAnswering() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        UUID runningSession = createSession(owner, projectId, viewId, "Corriendo");
        UUID queuedSession = createSession(owner, projectId, viewId, "En cola");
        CountDownLatch release = new CountDownLatch(1);
        when(chatAssistantProvider.chat(anyList())).thenAnswer(invocation -> {
            release.await(AWAIT_MS, TimeUnit.MILLISECONDS);
            return "respuesta del que corrio";
        });
        String runningQuestion = postMessage(owner, projectId, runningSession, "corre");
        String queuedQuestion = postMessage(owner, projectId, queuedSession, "espera");

        mockMvc.perform(ask(owner, projectId, runningSession, runningQuestion))
                .andExpect(status().isAccepted());
        // Determinism: the single worker must already be inside the latch before we cancel, so
        // the queued request is still queued and the running one is truly running.
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        JsonNode runningState = null;
        while (System.currentTimeMillis() < deadline) {
            JsonNode state = pendingState(owner, projectId, runningSession);
            if (state != null && "RUNNING".equals(state.path("state").asText())) {
                runningState = state;
                break;
            }
            Thread.sleep(50);
        }
        assertThat(runningState).isNotNull();
        assertThat(runningState.path("questionMessageId").asText()).isEqualTo(runningQuestion);

        mockMvc.perform(ask(owner, projectId, queuedSession, queuedQuestion))
                .andExpect(status().isAccepted());
        JsonNode queuedState = pendingState(owner, projectId, queuedSession);
        assertThat(queuedState).isNotNull();
        assertThat(queuedState.path("state").asText()).isEqualTo("QUEUED");

        mockMvc.perform(authorizedAs(owner, delete("/projects/{projectId}/sessions/{sessionId}/ai-request",
                        projectId, queuedSession)))
                .andExpect(status().isOk());
        // The running inference cannot be interrupted; only queued requests are cancellable (L38).
        mockMvc.perform(authorizedAs(owner, delete("/projects/{projectId}/sessions/{sessionId}/ai-request",
                        projectId, runningSession)))
                .andExpect(status().isConflict());

        release.countDown();
        awaitAiAnswer(owner, projectId, runningSession, runningQuestion);
        assertThat(messages(owner, projectId, queuedSession)).hasSize(1);
        assertThat(pendingState(owner, projectId, queuedSession)).isNull();
        mockMvc.perform(authorizedAs(owner, delete("/projects/{projectId}/sessions/{sessionId}/ai-request",
                        projectId, queuedSession)))
                .andExpect(status().isNotFound());
    }

    @Test
    void viewersCanAskAndForeignIdsAreRejected() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Con viewer");
        String viewer = username("viewer");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);
        String questionId = postMessage(owner, projectId, sessionId, "pregunta para el viewer");

        when(chatAssistantProvider.chat(anyList())).thenReturn("respuesta para el viewer");
        mockMvc.perform(ask(viewer, projectId, sessionId, questionId))
                .andExpect(status().isAccepted());
        awaitAiAnswer(viewer, projectId, sessionId, questionId);

        // A foreign project id is rejected without leaking anything (CA-06).
        String outsider = username("outsider");
        UUID foreignProject = createProject(outsider);
        bearer(outsider);
        UUID foreignSession = createSession(outsider, foreignProject, defaultViewId(foreignProject), "Ajena");
        String foreignQuestion = postMessage(outsider, foreignProject, foreignSession, "ajena");

        mockMvc.perform(ask(viewer, projectId, foreignSession, foreignQuestion))
                .andExpect(status().isNotFound());
        mockMvc.perform(ask(viewer, projectId, sessionId, foreignQuestion))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theProviderReceivesOnlyTheChosenDiagramAndThisSessionHistory() throws Exception {
        String ownerA = username("owner-a");
        UUID projectA = createProject(ownerA);
        UUID viewA = defaultViewId(projectA);
        seedClassifier(projectA, "ClienteA");
        UUID sessionA = createSession(ownerA, projectA, viewA, "Contexto A");
        String questionA = postMessage(ownerA, projectA, sessionA, "pregunta del proyecto A");

        String ownerB = username("owner-b");
        UUID projectB = createProject(ownerB);
        UUID viewB = defaultViewId(projectB);
        seedClassifier(projectB, "ClienteB");
        UUID sessionB = createSession(ownerB, projectB, viewB, "Contexto B");
        postMessage(ownerB, projectB, sessionB, "pregunta del proyecto B");

        when(chatAssistantProvider.chat(anyList())).thenReturn("respuesta");
        mockMvc.perform(ask(ownerA, projectA, sessionA, questionA))
                .andExpect(status().isAccepted());
        awaitAiAnswer(ownerA, projectA, sessionA, questionA);

        ArgumentCaptor<List<ChatAssistantProvider.Turn>> turns = ArgumentCaptor.captor();
        verify(chatAssistantProvider, timeout(5_000)).chat(turns.capture());
        String fullConversation = turns.getValue().stream()
                .map(ChatAssistantProvider.Turn::content)
                .reduce("", (left, right) -> left + "\n" + right);    assertThat(fullConversation).contains("ClienteA");
        assertThat(fullConversation).contains("pregunta del proyecto A");
        assertThat(fullConversation).doesNotContain("ClienteB");
        assertThat(fullConversation).doesNotContain("pregunta del proyecto B");
    }

    @Test
    void aFailingProviderRecordsOnlyTheFailureState() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Falla");
        String questionId = postMessage(owner, projectId, sessionId, "pregunta que falla");
        when(chatAssistantProvider.chat(anyList())).thenThrow(new RuntimeException("provider boom"));

        mockMvc.perform(ask(owner, projectId, sessionId, questionId))
                .andExpect(status().isAccepted());

        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (pendingState(owner, projectId, sessionId) != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(pendingState(owner, projectId, sessionId)).isNull();
        assertThat(messages(owner, projectId, sessionId)).hasSize(1);
        ArgumentCaptor<ProjectEvent> events = ArgumentCaptor.forClass(ProjectEvent.class);
        verify(eventBus, timeout(5_000).atLeastOnce()).publish(events.capture());
        assertThat(events.getAllValues().stream()
                .filter(event -> event.projectId().equals(projectId))
                .anyMatch(event -> "chat.ai.status".equals(event.type())
                        && sessionId.equals(event.payload().get("sessionId"))
                        && "FAILED".equals(event.payload().get("state")))).isTrue();
    }

    private String username(String role) {
        // Auth usernames are capped at 64 characters; a short prefix keeps room for the UUID suffix.
        return "ca-" + role + "-" + UUID.randomUUID();
    }

    private UUID createProject(String owner) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("name", "Chat AI " + UUID.randomUUID())))))
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

    private JsonNode messages(String username, UUID projectId, UUID sessionId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                        get("/projects/{projectId}/sessions/{sessionId}/messages", projectId, sessionId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private MockHttpServletRequestBuilder ask(String username, UUID projectId, UUID sessionId, String messageId)
            throws Exception {
        return authorizedAs(username, post("/projects/{projectId}/sessions/{sessionId}/ask", projectId, sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("messageId", messageId))));
    }

    private JsonNode pendingState(String username, UUID projectId, UUID sessionId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                        get("/projects/{projectId}/sessions/{sessionId}/ai-request", projectId, sessionId)))
                .andReturn();
        if (result.getResponse().getStatus() == 404) {
            return null;
        }
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode awaitAiAnswer(String username, UUID projectId, UUID sessionId, String questionId)
            throws Exception {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            for (JsonNode message : messages(username, projectId, sessionId)) {
                if ("AI".equals(message.path("kind").asText())
                        && questionId.equals(message.path("replyTo").asText(null))) {
                    return message;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No AI answer linked to " + questionId + " appeared within " + AWAIT_MS + "ms");
    }

    private void awaitNoPending(String username, UUID projectId, UUID sessionId) throws Exception {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (pendingState(username, projectId, sessionId) != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(pendingState(username, projectId, sessionId)).isNull();
    }

    private String errorCode(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("code").asText();
    }

    private void seedClassifier(UUID projectId, String name) {
        transactionTemplate.executeWithoutResult(status -> {
            var model = modelRepository.findByProjectId(projectId).orElseThrow();
            classifierRepository.saveAndFlush(Classifier.create(model, null, ClassifierKind.CLASS, name, false));
        });
    }
}
