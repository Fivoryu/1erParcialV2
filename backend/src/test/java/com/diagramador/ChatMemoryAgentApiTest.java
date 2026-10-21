package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.assistant.ChatAssistantProvider;
import com.diagramador.assistant.LlamaCppAssistantProvider;
import com.diagramador.domain.ProjectMemberRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

/** Public HTTP contracts for automatic extraction and bounded memory actions in shared chat. */
@SpringBootTest(properties = "diagrammer.assistant.enabled=true")
@AutoConfigureMockMvc
class ChatMemoryAgentApiTest extends AuthenticatedApiTest {

    private static final long AWAIT_MS = 30_000;
    private static final String MEMORY_ACTION = "memoryAction";
    private static final String SEARCH_PATH = "/projects/{projectId}/views/{viewId}/memories/search";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private LlamaCppAssistantProvider chatAssistantProvider;

    @Test
    void automaticExtractionPersistsMemoryForANewSession() throws Exception {
        String owner = username("automatic");
        UUID projectId = createProject(owner, "AutomaticMemoryProject");
        UUID viewId = defaultViewId(projectId);
        UUID firstSession = createSession(owner, projectId, viewId, "Primera conversación");
        String firstQuestion = postMessage(owner, projectId, firstSession, "Decidimos auto-memory-marker");
        UUID secondSession = createSession(owner, projectId, viewId, "Nueva conversación");
        String secondQuestion = postMessage(owner, projectId, secondSession, "¿Qué decisión quedó guardada?");

        when(chatAssistantProvider.chat(anyList())).thenReturn(
                extraction(memoryCandidate("auto-memory-marker", "DECISION", firstQuestion)),
                "La decisión quedó registrada.",
                extraction(""),
                "La decisión fue auto-memory-marker.");

        mockMvc.perform(ask(owner, projectId, firstSession, firstQuestion)).andExpect(status().isAccepted());
        assertThat(awaitAiAnswer(owner, projectId, firstSession, firstQuestion).path("body").asText())
                .isEqualTo("La decisión quedó registrada.");
        assertThat(memories(owner, projectId, viewId).findValuesAsText("content"))
                .contains("auto-memory-marker");

        mockMvc.perform(ask(owner, projectId, secondSession, secondQuestion)).andExpect(status().isAccepted());
        assertThat(awaitAiAnswer(owner, projectId, secondSession, secondQuestion).path("body").asText())
                .isEqualTo("La decisión fue auto-memory-marker.");

        ArgumentCaptor<List<ChatAssistantProvider.Turn>> calls = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(chatAssistantProvider,
                org.mockito.Mockito.timeout(5_000).times(4)).chat(calls.capture());
        String secondSessionContext = calls.getAllValues().get(3).stream()
                .map(ChatAssistantProvider.Turn::content).reduce("", (left, right) -> left + "\n" + right);
        assertThat(secondSessionContext).contains("auto-memory-marker");
        assertThat(secondSessionContext).contains("Contexto actual del diagrama");
    }

    @Test
    void failedAutomaticExtractionLeavesSourcesRetryableAndDoesNotDropTheChat() throws Exception {
        String owner = username("extraction-recovery");
        UUID projectId = createProject(owner, "ExtractionRecoveryProject");
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Recuperación");
        String questionId = postMessage(owner, projectId, sessionId, "Decidimos retryable-auto-marker");
        when(chatAssistantProvider.chat(anyList())).thenReturn("not a memory payload", "La conversación siguió.",
                extraction(memoryCandidate("retryable-auto-marker", "DECISION", questionId)));

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        assertThat(awaitAiAnswer(owner, projectId, sessionId, questionId).path("body").asText())
                .isEqualTo("La conversación siguió.");
        assertThat(memories(owner, projectId, viewId)).isEmpty();

        MvcResult retry = mockMvc.perform(authorizedAs(owner,
                        post("/projects/{projectId}/views/{viewId}/memories/extract", projectId, viewId)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(retry.getResponse().getContentAsByteArray())
                .path("memoriesCreated").asInt()).isEqualTo(1);
        assertThat(memories(owner, projectId, viewId).findValuesAsText("content"))
                .contains("retryable-auto-marker");
    }

    @Test
    void readSaveAndReviseActionsReturnTheirRealResultsBeforeANaturalAnswer() throws Exception {
        String owner = username("actions");
        UUID projectId = createProject(owner, "ActionProject");
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Acciones");
        String predecessorId = saveMemory(owner, projectId, viewId, "DECISION", "original-action-marker");
        String questionId = postMessage(owner, projectId, sessionId, "Lee, guarda y revisa una memoria");
        List<List<ChatAssistantProvider.Turn>> observed = new ArrayList<>();
        AtomicInteger response = new AtomicInteger();
        when(chatAssistantProvider.chat(anyList())).thenAnswer(invocation -> {
            List<ChatAssistantProvider.Turn> turns = List.copyOf(invocation.getArgument(0));
            observed.add(turns);
            return switch (response.getAndIncrement()) {
                case 0 -> extraction("");
                case 1 -> action(Map.of("operation", "read", "memoryId", predecessorId));
                case 2 -> action(Map.of("operation", "save", "kind", "HYPOTHESIS",
                        "content", "saved-action-marker"));
                case 3 -> action(Map.of("operation", "revise", "memoryId", predecessorId,
                        "kind", "DECISION", "content", "revised-action-marker"));
                default -> "Leí la memoria, guardé la hipótesis y revisé la decisión.";
            };
        });

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, projectId, sessionId, questionId);
        assertThat(answer.path("body").asText())
                .contains("Leí la memoria, guardé la hipótesis y revisé la decisión.");
        assertThat(observed).hasSize(5);
        assertThat(turnText(observed.get(2))).contains("original-action-marker");
        assertThat(turnText(observed.get(4))).contains("revised-action-marker");

        JsonNode stored = memories(owner, projectId, viewId);
        assertThat(findMemory(stored, predecessorId).path("status").asText()).isEqualTo("REPLACED");
        assertThat(findMemory(stored, predecessorId).path("replacedBy").asText()).isNotBlank();
        assertThat(stored.findValuesAsText("content"))
                .contains("saved-action-marker", "revised-action-marker");
    }

    @Test
    void unknownActionsAreRefusedWithoutChangingPublicMemoryState() throws Exception {
        String owner = username("unknown-action");
        UUID projectId = createProject(owner, "UnknownActionProject");
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Acción desconocida");
        JsonNode before = memories(owner, projectId, viewId);
        String questionId = postMessage(owner, projectId, sessionId, "Borra una memoria");
        String unknownAction = "{\"memoryAction\":{\"operation\":\"delete\",\"memoryId\":\""
                + UUID.randomUUID() + "\"}}";
        when(chatAssistantProvider.chat(anyList())).thenReturn(unknownAction, unknownAction);

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, projectId, sessionId, questionId);

        assertThat(memories(owner, projectId, viewId)).isEqualTo(before);
        assertThat(answer.path("body").asText()).doesNotContain("memoryAction").isNotBlank();
        verify(chatAssistantProvider, timeout(5_000).times(2)).chat(anyList());
    }

    @Test
    void malformedActionsAreRefusedWithoutChangingPublicMemoryState() throws Exception {
        String owner = username("malformed-action");
        UUID projectId = createProject(owner, "MalformedActionProject");
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Acción malformada");
        JsonNode before = memories(owner, projectId, viewId);
        String questionId = postMessage(owner, projectId, sessionId, "Guarda una memoria");
        String malformedAction = "{\"memoryAction\":{\"operation\":\"save\",\"kind\":\"DECISION\","
                + "\"content\":\"must-not-persist\",\"extra\":true}}";
        when(chatAssistantProvider.chat(anyList())).thenReturn(malformedAction, malformedAction);

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, projectId, sessionId, questionId);

        assertThat(memories(owner, projectId, viewId)).isEqualTo(before);
        assertThat(answer.path("body").asText()).doesNotContain("memoryAction").isNotBlank();
        verify(chatAssistantProvider, timeout(5_000).times(2)).chat(anyList());
    }

    @Test
    void oversizedSearchQueryIsRefusedBeforeSearching() throws Exception {
        String owner = username("oversized-query");
        UUID projectId = createProject(owner, "OversizedQueryProject");
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Consulta extensa");
        String questionId = postMessage(owner, projectId, sessionId, "Resume este diagrama");
        String oversized = action(Map.of("operation", "search", "query", "q".repeat(201), "limit", 5));
        when(chatAssistantProvider.chat(anyList())).thenReturn(oversized, oversized);

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, projectId, sessionId, questionId);

        assertThat(answer.path("body").asText()).doesNotContain("memoryAction");
        verify(chatAssistantProvider, timeout(5_000).times(2)).chat(anyList());
    }

    @Test
    void outOfRangeSearchLimitIsRefusedBeforeSearching() throws Exception {
        String owner = username("oversized-limit");
        UUID projectId = createProject(owner, "OversizedLimitProject");
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Límite extenso");
        String questionId = postMessage(owner, projectId, sessionId, "Resume este diagrama");
        String outOfRange = action(Map.of("operation", "search", "query", "decision", "limit", 6));
        when(chatAssistantProvider.chat(anyList())).thenReturn(outOfRange, outOfRange);

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, projectId, sessionId, questionId);

        assertThat(answer.path("body").asText()).doesNotContain("memoryAction");
        verify(chatAssistantProvider, timeout(5_000).times(2)).chat(anyList());
    }

    @Test
    void oversizedSaveContentIsRefusedWithoutMutation() throws Exception {
        String owner = username("oversized-content");
        UUID projectId = createProject(owner, "OversizedContentProject");
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Contenido extenso");
        String questionId = postMessage(owner, projectId, sessionId, "Guarda la decisión");
        String oversized = action(Map.of("operation", "save", "kind", "DECISION", "content", "x".repeat(2001)));
        when(chatAssistantProvider.chat(anyList())).thenReturn(oversized, oversized);

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, projectId, sessionId, questionId);

        assertThat(memories(owner, projectId, viewId)).isEmpty();
        assertThat(answer.path("body").asText()).doesNotContain("memoryAction");
        verify(chatAssistantProvider, timeout(5_000).times(2)).chat(anyList());
    }

    @Test
    void agentExecutesAtMostFourActionSteps() throws Exception {
        String owner = username("bounded-actions");
        UUID projectId = createProject(owner, "BoundedActionsProject");
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Pasos limitados");
        String questionId = postMessage(owner, projectId, sessionId, "Guarda solo si la operación fue confirmada");
        AtomicInteger response = new AtomicInteger();
        when(chatAssistantProvider.chat(anyList())).thenAnswer(invocation -> {
            int index = response.getAndIncrement();
            if (index == 0) {
                return extraction("");
            }
            if (index <= 4) {
                return action(Map.of("operation", "read", "memoryId", UUID.randomUUID().toString()));
            }
            return action(Map.of("operation", "save", "kind", "DECISION", "content", "fifth-action-must-not-run"));
        });

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, projectId, sessionId, questionId);

        assertThat(memories(owner, projectId, viewId)).isEmpty();
        assertThat(answer.path("body").asText()).doesNotContain("fifth-action-must-not-run");
        verify(chatAssistantProvider, timeout(5_000).times(6)).chat(anyList());
    }

    @Test
    void quotedActionJsonIsNotExecutedOrDisplayedAsAProtocolDump() throws Exception {
        String owner = username("quoted-action");
        UUID projectId = createProject(owner, "QuotedActionProject");
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "JSON citado");
        String questionId = postMessage(owner, projectId, sessionId, "Explica el formato");
        String quotedAction = "Texto explicativo: {\"memoryAction\":{\"operation\":\"save\","
                + "\"kind\":\"DECISION\",\"content\":\"quoted-must-not-persist\"}}";
        when(chatAssistantProvider.chat(anyList())).thenReturn(quotedAction, quotedAction);

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, projectId, sessionId, questionId);

        assertThat(memories(owner, projectId, viewId).findValuesAsText("content"))
                .doesNotContain("quoted-must-not-persist");
        assertThat(answer.path("body").asText()).doesNotContain("memoryAction");
        verify(chatAssistantProvider, timeout(5_000).times(2)).chat(anyList());
    }

    @Test
    void successfulWriteIsReportedTruthfullyWhenLaterModelWorkFails() throws Exception {
        String owner = username("partial-write");
        UUID projectId = createProject(owner, "PartialWriteProject");
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Efecto parcial");
        String questionId = postMessage(owner, projectId, sessionId, "Guarda la decisión y explicala");
        AtomicInteger response = new AtomicInteger();
        when(chatAssistantProvider.chat(anyList())).thenAnswer(invocation -> switch (response.getAndIncrement()) {
            case 0 -> extraction("");
            case 1 -> action(Map.of("operation", "save", "kind", "DECISION",
                    "content", "confirmed-partial-write"));
            default -> throw new IllegalStateException("final provider failure");
        });

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, projectId, sessionId, questionId);

        assertThat(answer.path("body").asText()).contains("Memoria guardada por el servidor");
        assertThat(memories(owner, projectId, viewId).findValuesAsText("content"))
                .contains("confirmed-partial-write");
    }

    @Test
    void repeatedSaveActionDoesNotCreateDuplicateMemories() throws Exception {
        String owner = username("repeated-action");
        UUID projectId = createProject(owner, "RepeatedActionProject");
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Acción repetida");
        String questionId = postMessage(owner, projectId, sessionId, "Guarda una sola vez");
        String repeated = action(Map.of("operation", "save", "kind", "DECISION",
                "content", "single-write-marker"));
        when(chatAssistantProvider.chat(anyList())).thenReturn(extraction(""), repeated, repeated,
                "La decisión quedó guardada una sola vez.");

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        assertThat(awaitAiAnswer(owner, projectId, sessionId, questionId).path("body").asText())
                .contains("La decisión quedó guardada una sola vez.");

        JsonNode stored = memories(owner, projectId, viewId);
        assertThat(stored.findValuesAsText("content").stream()
                .filter("single-write-marker"::equals)).hasSize(1);
    }

    @Test
    void modelCannotClaimMemoryWasSavedWithoutAPersistedWrite() throws Exception {
        String owner = username("unconfirmed-write");
        UUID projectId = createProject(owner, "UnconfirmedWriteProject");
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Escritura no confirmada");
        String questionId = postMessage(owner, projectId, sessionId, "Guarda esta decisión");
        when(chatAssistantProvider.chat(anyList())).thenReturn(
                "La memoria fue guardada correctamente.", "La memoria fue guardada correctamente.");

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, projectId, sessionId, questionId);

        assertThat(memories(owner, projectId, viewId)).isEmpty();
        assertThat(answer.path("body").asText()).doesNotContain("guardada correctamente");
        verify(chatAssistantProvider, timeout(5_000).times(2)).chat(anyList());
    }

    @Test
    void sharedSearchExcludesProjectsMissingOneActiveCollaborator() throws Exception {
        String owner = username("shared-excluded");
        String collaborator = username("shared-collaborator");
        UUID currentProject = createProject(owner, "SharedCurrentProject");
        UUID currentView = defaultViewId(currentProject);
        bearer(collaborator);
        addMembership(collaborator, currentProject, ProjectMemberRole.EDITOR);
        UUID externalProject = createProject(owner, "SharedPrivateProject");
        UUID externalView = defaultViewId(externalProject);
        saveMemory(owner, externalProject, externalView, "DECISION", "excluded-shared-marker externalneedle");
        UUID sessionId = createSession(owner, currentProject, currentView, "Búsqueda compartida");
        String questionId = postMessage(owner, currentProject, sessionId, "Busca en otros diagramas");
        when(chatAssistantProvider.chat(anyList())).thenReturn(extraction(""),
                action(Map.of("operation", "search", "query", "externalneedle",
                        "includeAccessibleProjects", true, "limit", 5)),
                "No encontré ese dato en fuentes comunes.");

        mockMvc.perform(ask(owner, currentProject, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, currentProject, sessionId, questionId);

        assertThat(answer.path("body").asText()).doesNotContain("excluded-shared-marker");
        assertThat(answer.path("body").asText()).doesNotContain("SharedPrivateProject");
        ArgumentCaptor<List<ChatAssistantProvider.Turn>> calls = ArgumentCaptor.captor();
        verify(chatAssistantProvider, timeout(5_000).times(3)).chat(calls.capture());
        assertThat(turnText(calls.getAllValues().get(2))).doesNotContain("excluded-shared-marker");
    }

    @Test
    void sharedSearchIncludesExternalProjectsAccessibleToEveryActiveCollaborator() throws Exception {
        String owner = username("shared-included");
        String collaborator = username("shared-collaborator");
        UUID currentProject = createProject(owner, "CommonCurrentProject");
        UUID currentView = defaultViewId(currentProject);
        bearer(collaborator);
        addMembership(collaborator, currentProject, ProjectMemberRole.EDITOR);
        UUID externalProject = createProject(owner, "CommonExternalProject");
        UUID externalView = defaultViewId(externalProject);
        saveMemory(owner, externalProject, externalView, "DECISION", "included-shared-marker sharedneedle");
        for (int index = 0; index < 5; index++) {
            saveMemory(owner, currentProject, currentView, "DECISION", "local-sharedneedle-" + index);
        }
        addMembership(collaborator, externalProject, ProjectMemberRole.VIEWER);
        UUID sessionId = createSession(owner, currentProject, currentView, "Búsqueda común");
        String questionId = postMessage(owner, currentProject, sessionId,
                "Busca en otros diagramas una decisión común");
        when(chatAssistantProvider.chat(anyList())).thenReturn(extraction(""),
                action(Map.of("operation", "search", "query", "sharedneedle",
                        "includeAccessibleProjects", true, "limit", 5)),
                "Encontré la decisión.");

        mockMvc.perform(ask(owner, currentProject, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, currentProject, sessionId, questionId);

        assertThat(answer.path("body").asText()).contains("CommonExternalProject");
        ArgumentCaptor<List<ChatAssistantProvider.Turn>> calls = ArgumentCaptor.captor();
        verify(chatAssistantProvider, timeout(5_000).times(3)).chat(calls.capture());
        assertThat(turnText(calls.getAllValues().get(2))).contains("included-shared-marker");
    }

    @Test
    void modelCannotExpandSearchScopeWithoutAnExplicitCrossDiagramRequest() throws Exception {
        String owner = username("shared-unrequested");
        String collaborator = username("shared-collaborator");
        UUID currentProject = createProject(owner, "UnrequestedCurrentProject");
        UUID currentView = defaultViewId(currentProject);
        bearer(collaborator);
        addMembership(collaborator, currentProject, ProjectMemberRole.EDITOR);
        UUID externalProject = createProject(owner, "UnrequestedExternalProject");
        UUID externalView = defaultViewId(externalProject);
        saveMemory(owner, externalProject, externalView, "DECISION", "unrequested-secret-content");
        addMembership(collaborator, externalProject, ProjectMemberRole.VIEWER);
        UUID sessionId = createSession(owner, currentProject, currentView, "Búsqueda externa denegada");
        String questionId = postMessage(owner, currentProject, sessionId,
                "No busques en otros diagramas; ¿qué se decidió en este diagrama?");
        when(chatAssistantProvider.chat(anyList())).thenReturn(extraction(""),
                action(Map.of("operation", "search", "query", "unrequested-secret-content",
                        "includeAccessibleProjects", true, "limit", 5)));

        mockMvc.perform(ask(owner, currentProject, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, currentProject, sessionId, questionId);

        assertThat(answer.path("body").asText()).doesNotContain("unrequested-secret-content");
        assertThat(answer.path("body").asText()).doesNotContain("UnrequestedExternalProject");
        verify(chatAssistantProvider, timeout(5_000).times(2)).chat(anyList());
    }

    @Test
    void sharedSaveRefusesCrossSourceMemoryWhenAudienceAccessWasRevokedBeforeWrite() throws Exception {
        String owner = username("shared-save-revoked");
        String collaborator = username("ss-collab");
        UUID currentProject = createProject(owner, "SharedSaveCurrentProject");
        UUID currentView = defaultViewId(currentProject);
        bearer(collaborator);
        addMembership(collaborator, currentProject, ProjectMemberRole.EDITOR);
        UUID externalProject = createProject(owner, "SharedSaveExternalProject");
        UUID externalView = defaultViewId(externalProject);
        String sourceContent = "source-derived-save-marker";
        saveMemory(owner, externalProject, externalView, "DECISION", sourceContent);
        addMembership(collaborator, externalProject, ProjectMemberRole.VIEWER);
        UUID sessionId = createSession(owner, currentProject, currentView, "Guardado desde fuente común");
        String questionId = postMessage(owner, currentProject, sessionId,
                "Busca en otros diagramas y guarda la decisión encontrada");
        JsonNode currentBefore = memories(owner, currentProject, currentView);
        JsonNode externalBefore = memories(owner, externalProject, externalView);
        CountDownLatch writeActionReady = new CountDownLatch(1);
        CountDownLatch releaseWriteAction = new CountDownLatch(1);
        AtomicReference<String> contextAtWrite = new AtomicReference<>();
        AtomicInteger response = new AtomicInteger();
        when(chatAssistantProvider.chat(anyList())).thenAnswer(invocation -> switch (response.getAndIncrement()) {
            case 0 -> extraction("");
            case 1 -> action(Map.of("operation", "search", "query", "source-derived-save-marker",
                    "includeAccessibleProjects", true, "limit", 5));
            case 2 -> {
                contextAtWrite.set(turnText(invocation.getArgument(0)));
                writeActionReady.countDown();
                if (!releaseWriteAction.await(AWAIT_MS, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("save action test latch timed out");
                }
                yield action(Map.of("operation", "save", "kind", "DECISION",
                        "content", sourceContent + " copied to current diagram"));
            }
            default -> "I saved the decision from the source project.";
        });

        mockMvc.perform(ask(owner, currentProject, sessionId, questionId)).andExpect(status().isAccepted());
        boolean reachedWrite = writeActionReady.await(AWAIT_MS, TimeUnit.MILLISECONDS);
        if (!reachedWrite) {
            releaseWriteAction.countDown();
            throw new AssertionError("The provider did not reach the save action after external search.");
        }
        try {
            assertThat(contextAtWrite.get()).contains("SERVER_MEMORY_RESULT", sourceContent);
            jdbcTemplate.update("UPDATE diagrammer.project_member SET status = 'REMOVED' "
                            + "WHERE project_id = ?::uuid AND user_id = ?::uuid",
                    externalProject.toString(), userIdFor(collaborator).toString());
        } finally {
            releaseWriteAction.countDown();
        }
        awaitNoPending(owner, currentProject, sessionId);

        assertThat(memories(owner, currentProject, currentView)).isEqualTo(currentBefore);
        assertThat(memories(owner, externalProject, externalView)).isEqualTo(externalBefore);
        JsonNode history = messages(owner, currentProject, sessionId);
        assertThat(history).hasSize(1);
        assertThat(history.toString()).doesNotContain(sourceContent, "SharedSaveExternalProject");
        verify(chatAssistantProvider, timeout(5_000).times(4)).chat(anyList());
    }

    @Test
    void sharedReviseRefusesCrossSourceMemoryWhenAudienceAccessWasRevokedBeforeWrite() throws Exception {
        String owner = username("shared-revise-revoked");
        String collaborator = username("sr-collab");
        UUID currentProject = createProject(owner, "SharedReviseCurrentProject");
        UUID currentView = defaultViewId(currentProject);
        bearer(collaborator);
        addMembership(collaborator, currentProject, ProjectMemberRole.EDITOR);
        UUID externalProject = createProject(owner, "SharedReviseExternalProject");
        UUID externalView = defaultViewId(externalProject);
        String sourceContent = "source-derived-revise-marker";
        saveMemory(owner, externalProject, externalView, "DECISION", sourceContent);
        addMembership(collaborator, externalProject, ProjectMemberRole.VIEWER);
        String predecessorId = saveMemory(owner, currentProject, currentView, "DECISION", "local predecessor");
        UUID sessionId = createSession(owner, currentProject, currentView, "Revisión desde fuente común");
        String questionId = postMessage(owner, currentProject, sessionId,
                "Busca en otros diagramas y revisa la decisión local con lo encontrado");
        JsonNode currentBefore = memories(owner, currentProject, currentView);
        JsonNode externalBefore = memories(owner, externalProject, externalView);
        CountDownLatch writeActionReady = new CountDownLatch(1);
        CountDownLatch releaseWriteAction = new CountDownLatch(1);
        AtomicReference<String> contextAtWrite = new AtomicReference<>();
        AtomicInteger response = new AtomicInteger();
        when(chatAssistantProvider.chat(anyList())).thenAnswer(invocation -> switch (response.getAndIncrement()) {
            case 0 -> extraction("");
            case 1 -> action(Map.of("operation", "search", "query", "source-derived-revise-marker",
                    "includeAccessibleProjects", true, "limit", 5));
            case 2 -> {
                contextAtWrite.set(turnText(invocation.getArgument(0)));
                writeActionReady.countDown();
                if (!releaseWriteAction.await(AWAIT_MS, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("revise action test latch timed out");
                }
                yield action(Map.of("operation", "revise", "memoryId", predecessorId,
                        "kind", "DECISION", "content", sourceContent + " revised decision"));
            }
            default -> "I revised the local decision from the source project.";
        });

        mockMvc.perform(ask(owner, currentProject, sessionId, questionId)).andExpect(status().isAccepted());
        boolean reachedWrite = writeActionReady.await(AWAIT_MS, TimeUnit.MILLISECONDS);
        if (!reachedWrite) {
            releaseWriteAction.countDown();
            throw new AssertionError("The provider did not reach the revise action after external search.");
        }
        try {
            assertThat(contextAtWrite.get()).contains("SERVER_MEMORY_RESULT", sourceContent);
            jdbcTemplate.update("UPDATE diagrammer.project_member SET status = 'REMOVED' "
                            + "WHERE project_id = ?::uuid AND user_id = ?::uuid",
                    externalProject.toString(), userIdFor(collaborator).toString());
        } finally {
            releaseWriteAction.countDown();
        }
        awaitNoPending(owner, currentProject, sessionId);

        assertThat(memories(owner, currentProject, currentView)).isEqualTo(currentBefore);
        assertThat(findMemory(memories(owner, currentProject, currentView), predecessorId)
                .path("status").asText()).isEqualTo("ACTIVE");
        assertThat(memories(owner, externalProject, externalView)).isEqualTo(externalBefore);
        JsonNode history = messages(owner, currentProject, sessionId);
        assertThat(history).hasSize(1);
        assertThat(history.toString()).doesNotContain(sourceContent, "SharedReviseExternalProject");
        verify(chatAssistantProvider, timeout(5_000).times(4)).chat(anyList());
    }

    @Test
    void readAnswerCanDescribeASavedMemoryWithoutClaimingANewWrite() throws Exception {
        String owner = username("read-saved-description");
        UUID projectId = createProject(owner, "ReadSavedDescriptionProject");
        UUID viewId = defaultViewId(projectId);
        String content = "Pedido tiene varios productos";
        String memoryId = saveMemory(owner, projectId, viewId, "DECISION", content);
        JsonNode before = memories(owner, projectId, viewId);
        UUID sessionId = createSession(owner, projectId, viewId, "Descripción de lectura");
        String questionId = postMessage(owner, projectId, sessionId, "¿Qué indica la memoria guardada?");
        String naturalAnswer = "La memoria guardada indica que Pedido tiene varios productos.";
        when(chatAssistantProvider.chat(anyList())).thenReturn(extraction(""),
                action(Map.of("operation", "read", "memoryId", memoryId)), naturalAnswer);

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, projectId, sessionId, questionId);

        assertThat(answer.path("body").asText()).isEqualTo(naturalAnswer);
        assertThat(memories(owner, projectId, viewId)).isEqualTo(before);
        verify(chatAssistantProvider, timeout(5_000).times(3)).chat(anyList());
    }

    @Test
    void revokingCommonAccessBeforePublicationFailsClosedWithoutAnAnswer() throws Exception {
        String owner = username("shared-revoked");
        String collaborator = username("shared-collaborator");
        UUID currentProject = createProject(owner, "RevocationCurrentProject");
        UUID currentView = defaultViewId(currentProject);
        bearer(collaborator);
        addMembership(collaborator, currentProject, ProjectMemberRole.EDITOR);
        UUID externalProject = createProject(owner, "RevocationExternalProject");
        UUID externalView = defaultViewId(externalProject);
        saveMemory(owner, externalProject, externalView, "DECISION", "revoked-shared-marker");
        addMembership(collaborator, externalProject, ProjectMemberRole.VIEWER);
        UUID sessionId = createSession(owner, currentProject, currentView, "Revocación");
        String questionId = postMessage(owner, currentProject, sessionId,
                "Consulta en otros diagramas una fuente común");
        CountDownLatch finalAnswerStarted = new CountDownLatch(1);
        CountDownLatch releaseFinalAnswer = new CountDownLatch(1);
        AtomicInteger response = new AtomicInteger();
        when(chatAssistantProvider.chat(anyList())).thenAnswer(invocation -> switch (response.getAndIncrement()) {
            case 0 -> extraction("");
            case 1 -> action(Map.of("operation", "search", "query", "revoked-shared-marker",
                    "includeAccessibleProjects", true, "limit", 5));
            default -> {
                finalAnswerStarted.countDown();
                if (!releaseFinalAnswer.await(AWAIT_MS, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("final answer test latch timed out");
                }
                yield "The source was revoked before publication.";
            }
        });

        mockMvc.perform(ask(owner, currentProject, sessionId, questionId)).andExpect(status().isAccepted());
        assertThat(finalAnswerStarted.await(AWAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
        jdbcTemplate.update("UPDATE diagrammer.project_member SET status = 'REMOVED' "
                        + "WHERE project_id = ?::uuid AND user_id = ?::uuid",
                externalProject.toString(), userIdFor(collaborator).toString());
        releaseFinalAnswer.countDown();
        awaitNoPending(owner, currentProject, sessionId);

        JsonNode history = messages(owner, currentProject, sessionId);
        assertThat(history).hasSize(1);
        assertThat(history.toString()).doesNotContain("revoked-shared-marker");
        assertThat(history.toString()).doesNotContain("RevocationExternalProject");
    }

    @Test
    void queuedAsksRestoreTheInitiatingActorBetweenWorkerRuns() throws Exception {
        String firstOwner = username("actor-first");
        String secondOwner = username("actor-second");
        UUID firstProject = createProject(firstOwner, "ActorFirstProject");
        UUID secondProject = createProject(secondOwner, "ActorSecondProject");
        UUID firstView = defaultViewId(firstProject);
        UUID secondView = defaultViewId(secondProject);
        UUID firstSession = createSession(firstOwner, firstProject, firstView, "Actor A");
        UUID secondSession = createSession(secondOwner, secondProject, secondView, "Actor B");
        String firstQuestion = postMessage(firstOwner, firstProject, firstSession, "Guarda como actor A");
        String secondQuestion = postMessage(secondOwner, secondProject, secondSession, "Guarda como actor B");
        AtomicInteger response = new AtomicInteger();
        when(chatAssistantProvider.chat(anyList())).thenAnswer(invocation -> switch (response.getAndIncrement()) {
            case 0 -> extraction("");
            case 1 -> action(Map.of("operation", "save", "kind", "DECISION", "content", "actor-first-marker"));
            case 2 -> "Guardado para actor A.";
            case 3 -> extraction("");
            case 4 -> action(Map.of("operation", "save", "kind", "DECISION", "content", "actor-second-marker"));
            default -> "Guardado para actor B.";
        });

        mockMvc.perform(ask(firstOwner, firstProject, firstSession, firstQuestion)).andExpect(status().isAccepted());
        assertThat(awaitAiAnswer(firstOwner, firstProject, firstSession, firstQuestion).path("body").asText())
                .contains("Guardado para actor A.");
        awaitNoPending(firstOwner, firstProject, firstSession);
        mockMvc.perform(ask(secondOwner, secondProject, secondSession, secondQuestion)).andExpect(status().isAccepted());
        assertThat(awaitAiAnswer(secondOwner, secondProject, secondSession, secondQuestion).path("body").asText())
                .contains("Guardado para actor B.");

        assertThat(memories(firstOwner, firstProject, firstView).findValuesAsText("content"))
                .contains("actor-first-marker");
        assertThat(memories(secondOwner, secondProject, secondView).findValuesAsText("content"))
                .contains("actor-second-marker");
    }

    @Test
    void automaticExtractionConsumesOnlyTheBoundedCommandBatchItDelivered() throws Exception {
        String owner = username("bounded-batch");
        UUID projectId = createProject(owner, "BoundedBatchProject");
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Lote acotado");
        for (int index = 0; index < 21; index++) {
            createClassifierCommand(owner, projectId, "BoundedClassifier" + index);
        }
        long pendingBefore = pendingChanges(owner, projectId).path("pendingChanges").asLong();
        assertThat(pendingBefore).isGreaterThan(20);
        String questionId = postMessage(owner, projectId, sessionId, "Resume los cambios pendientes");
        when(chatAssistantProvider.chat(anyList())).thenReturn(extraction(""), "Cambios revisados.");

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        awaitAiAnswer(owner, projectId, sessionId, questionId);

        assertThat(pendingChanges(owner, projectId).path("pendingChanges").asLong())
                .isEqualTo(pendingBefore - 20);
    }

    @Test
    void explicitSaveRequestIsExecutedByTheServerWhenTheModelOnlyAnswersInText() throws Exception {
        String owner = username("explicit-save");
        UUID projectId = createProject(owner, "ExplicitSaveProject");
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Guardado explícito");
        String questionId = postMessage(owner, projectId, sessionId,
                "Guardá como hipótesis que la conciliación genera el estado det-save-marker antes del cierre. "
                        + "Decime en una frase qué registraste.");
        when(chatAssistantProvider.chat(anyList())).thenReturn(extraction(""), "Registré la hipótesis.");

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, projectId, sessionId, questionId);

        JsonNode stored = memories(owner, projectId, viewId);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).path("kind").asText()).isEqualTo("HYPOTHESIS");
        assertThat(stored.get(0).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(stored.get(0).path("content").asText())
                .isEqualTo("la conciliación genera el estado det-save-marker antes del cierre");
        assertThat(answer.path("body").asText()).contains("Registré la hipótesis.")
                .contains("Memoria guardada por el servidor");
        ArgumentCaptor<List<ChatAssistantProvider.Turn>> calls = ArgumentCaptor.captor();
        verify(chatAssistantProvider, timeout(5_000).times(2)).chat(calls.capture());
        assertThat(turnText(calls.getAllValues().get(1))).contains("SERVER_MEMORY_RESULT")
                .contains("det-save-marker");
    }

    @Test
    void explicitReviseRequestReplacesTheUniqueMatchingActiveMemory() throws Exception {
        String owner = username("explicit-revise");
        UUID projectId = createProject(owner, "ExplicitReviseProject");
        UUID viewId = defaultViewId(projectId);
        String oldId = saveMemory(owner, projectId, viewId, "HYPOTHESIS",
                "La conciliación genera el estado REV-OLD-7 antes del cierre.");
        String otherId = saveMemory(owner, projectId, viewId, "HYPOTHESIS", "Otra hipótesis REV-OTHER-8.");
        UUID sessionId = createSession(owner, projectId, viewId, "Revisión explícita");
        String earlierId = postMessage(owner, projectId, sessionId, "Recordemos que REV-OLD-7 aplica antes del cierre.");
        String questionId = postMessage(owner, projectId, sessionId,
                "Revisá la hipótesis activa que contiene REV-OLD-7: debe indicar REV-NEW-7 después del cierre. "
                        + "¿Cuál quedó como versión vigente?");
        // Automatic extraction also stores echoes of the explicit memory, from an earlier message and from the
        // request itself; they repeat the identifier but must not make the revision target ambiguous.
        when(chatAssistantProvider.chat(anyList())).thenReturn(
                extraction(memoryCandidate("REV-OLD-7 aplica antes del cierre.", "HYPOTHESIS", earlierId) + ","
                        + memoryCandidate("Revisar la hipótesis REV-OLD-7 para indicar REV-NEW-7.", "HYPOTHESIS",
                                questionId)),
                "Quedó vigente la versión revisada.");

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, projectId, sessionId, questionId);

        JsonNode stored = memories(owner, projectId, viewId);
        JsonNode previous = findMemory(stored, oldId);
        assertThat(previous.path("status").asText()).isEqualTo("REPLACED");
        JsonNode replacement = findMemory(stored, previous.path("replacedBy").asText());
        assertThat(replacement.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(replacement.path("kind").asText()).isEqualTo("HYPOTHESIS");
        assertThat(replacement.path("content").asText()).isEqualTo(
                "La conciliación genera el estado REV-OLD-7 antes del cierre. "
                        + "Revisión: debe indicar REV-NEW-7 después del cierre.");
        assertThat(findMemory(stored, otherId).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(answer.path("body").asText()).contains("Memoria revisada por el servidor");
    }

    @Test
    void explicitReviseRequestWithAnAmbiguousTargetLeavesMemoriesToTheModel() throws Exception {
        String owner = username("ambiguous-revise");
        UUID projectId = createProject(owner, "AmbiguousReviseProject");
        UUID viewId = defaultViewId(projectId);
        saveMemory(owner, projectId, viewId, "HYPOTHESIS", "Primera hipótesis AMB-1 del cierre.");
        saveMemory(owner, projectId, viewId, "HYPOTHESIS", "Segunda hipótesis AMB-1 del cierre.");
        UUID sessionId = createSession(owner, projectId, viewId, "Revisión ambigua");
        String questionId = postMessage(owner, projectId, sessionId,
                "Revisá la hipótesis que contiene AMB-1: debe indicar AMB-2.");
        when(chatAssistantProvider.chat(anyList())).thenReturn(extraction(""), "¿Cuál de las dos hipótesis revisás?");

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, projectId, sessionId, questionId);

        JsonNode stored = memories(owner, projectId, viewId);
        assertThat(stored).hasSize(2);
        assertThat(stored.findValuesAsText("status")).containsOnly("ACTIVE");
        assertThat(answer.path("body").asText()).isEqualTo("¿Cuál de las dos hipótesis revisás?");
    }

    @Test
    void explicitCrossDiagramRequestSearchesCommonProjectsWithoutAModelAction() throws Exception {
        String owner = username("explicit-search");
        String collaborator = username("det-collab");
        UUID currentProject = createProject(owner, "DeterministicCurrentProject");
        UUID currentView = defaultViewId(currentProject);
        bearer(collaborator);
        addMembership(collaborator, currentProject, ProjectMemberRole.EDITOR);
        UUID externalProject = createProject(owner, "DeterministicExternalProject");
        UUID externalView = defaultViewId(externalProject);
        saveMemory(owner, externalProject, externalView, "DECISION", "included-det-marker detneedle7");
        addMembership(collaborator, externalProject, ProjectMemberRole.VIEWER);
        UUID sessionId = createSession(owner, currentProject, currentView, "Búsqueda determinista");
        String questionId = postMessage(owner, currentProject, sessionId,
                "Buscá en otros diagramas la regla detneedle7. ¿En qué proyecto aparece?");
        when(chatAssistantProvider.chat(anyList())).thenReturn(extraction(""), "Encontré la regla.");

        mockMvc.perform(ask(owner, currentProject, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, currentProject, sessionId, questionId);

        assertThat(answer.path("body").asText()).contains("Encontré la regla.")
                .contains("DeterministicExternalProject");
        ArgumentCaptor<List<ChatAssistantProvider.Turn>> calls = ArgumentCaptor.captor();
        verify(chatAssistantProvider, timeout(5_000).times(2)).chat(calls.capture());
        assertThat(turnText(calls.getAllValues().get(1))).contains("SERVER_MEMORY_RESULT")
                .contains("included-det-marker");
    }

    @Test
    void savedAsHypothesisClaimWithoutAPersistedWriteIsReplacedBySafeAnswer() throws Exception {
        String owner = username("hypothesis-claim");
        UUID projectId = createProject(owner, "HypothesisClaimProject");
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Afirmación sin escritura");
        String questionId = postMessage(owner, projectId, sessionId, "Anotalo por favor");
        when(chatAssistantProvider.chat(anyList())).thenReturn(extraction(""),
                "Registré como hipótesis: claim-marker antes del cierre.");

        mockMvc.perform(ask(owner, projectId, sessionId, questionId)).andExpect(status().isAccepted());
        JsonNode answer = awaitAiAnswer(owner, projectId, sessionId, questionId);

        assertThat(memories(owner, projectId, viewId)).isEmpty();
        assertThat(answer.path("body").asText())
                .isEqualTo("No se confirmó ningún cambio de memoria; no puedo afirmar que se haya guardado.");
    }

    private String username(String role) {
        return "cma-" + role + "-" + UUID.randomUUID();
    }

    private UUID createProject(String owner, String name) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name, "description", "context for " + name)))))
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
                                .content(json(Map.of("title", title)))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText());
    }

    private String postMessage(String username, UUID projectId, UUID sessionId, String body) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                        post("/projects/{projectId}/sessions/{sessionId}/messages", projectId, sessionId)
                                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("body", body)))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("id").asText();
    }

    private MockHttpServletRequestBuilder ask(String username, UUID projectId, UUID sessionId, String messageId)
            throws Exception {
        return authorizedAs(username,
                post("/projects/{projectId}/sessions/{sessionId}/ask", projectId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("messageId", messageId))));
    }

    private JsonNode messages(String username, UUID projectId, UUID sessionId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                        get("/projects/{projectId}/sessions/{sessionId}/messages", projectId, sessionId)))
                .andExpect(status().isOk())
                .andReturn();
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
        while (System.currentTimeMillis() < deadline) {
            MvcResult result = mockMvc.perform(authorizedAs(username,
                            get("/projects/{projectId}/sessions/{sessionId}/ai-request", projectId, sessionId)))
                    .andReturn();
            if (result.getResponse().getStatus() == 404) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("AI request remained pending for session " + sessionId);
    }

    private JsonNode memories(String username, UUID projectId, UUID viewId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                        get("/projects/{projectId}/views/{viewId}/memories", projectId, viewId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String saveMemory(String username, UUID projectId, UUID viewId, String kind, String content)
            throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                        post("/projects/{projectId}/views/{viewId}/memories", projectId, viewId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("kind", kind, "content", content)))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("memoryId").asText();
    }

    private JsonNode pendingChanges(String username, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                        get("/projects/{projectId}/model/changes/pending-count", projectId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private void createClassifierCommand(String owner, UUID projectId, String name) throws Exception {
        JsonNode model = readModel(owner, projectId);
        Map<String, Object> envelope = Map.of(
                "operationId", UUID.randomUUID(),
                "type", "classifier.create",
                "projectId", projectId,
                "modelId", model.path("id").asText(),
                "baseRevision", model.path("revision").asLong(),
                "targets", List.of(),
                "preconditions", List.of(),
                "payload", Map.of("id", UUID.randomUUID(), "name", name, "kind", "CLASS"),
                "origin", "MANUAL",
                "idempotencyKey", UUID.randomUUID());
        mockMvc.perform(authorizedAs(owner,
                        post("/projects/{projectId}/commands", projectId)
                                .contentType(MediaType.APPLICATION_JSON).content(json(envelope))))
                .andExpect(status().isOk());
    }

    private JsonNode readModel(String username, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, get("/projects/{projectId}/model", projectId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String extraction(String memories) {
        return "{\"memories\":[" + memories + "]}";
    }

    private String memoryCandidate(String content, String kind, String sourceMessageId) {
        return "{\"content\":\"" + content + "\",\"kind\":\"" + kind
                + "\",\"sourceMessageId\":\"" + sourceMessageId + "\"}";
    }

    private String action(Map<String, Object> action) throws Exception {
        return json(Map.of(MEMORY_ACTION, action));
    }

    private String turnText(List<ChatAssistantProvider.Turn> turns) {
        return turns.stream().map(ChatAssistantProvider.Turn::content)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private JsonNode findMemory(JsonNode memories, String memoryId) {
        for (JsonNode memory : memories) {
            if (memoryId.equals(memory.path("memoryId").asText())) {
                return memory;
            }
        }
        throw new AssertionError("No memory with id " + memoryId + " in " + memories);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
