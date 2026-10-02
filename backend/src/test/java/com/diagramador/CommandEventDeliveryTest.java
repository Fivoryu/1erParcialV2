package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.collaboration.ProjectEvent;
import com.diagramador.collaboration.ProjectEventBus;
import com.diagramador.domain.ProjectMemberRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class CommandEventDeliveryTest extends AuthenticatedApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectEventBus eventBus;

    private final List<MvcResult> openStreams = new ArrayList<>();

    @AfterEach
    void completeOpenStreams() {
        for (MvcResult result : openStreams) {
            if (result.getRequest().isAsyncStarted()) {
                result.getRequest().getAsyncContext().complete();
            }
        }
        openStreams.clear();
    }

    @Test
    void acceptedCommandReachesTheActorAndAnotherProjectMember() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID modelId = modelId(owner, projectId);
        String actor = username("actor");
        bearer(actor);
        addMembership(actor, projectId, ProjectMemberRole.EDITOR);

        MvcResult actorStream = openStream(actor, projectId);
        MvcResult otherMemberStream = openStream(owner, projectId);
        UUID operationId = UUID.randomUUID();
        MvcResult commandResponse = mockMvc.perform(authorizedAs(actor,
                post("/projects/{projectId}/commands", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(commandEnvelope(operationId, projectId, modelId, 1L))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode commandResult = objectMapper.readTree(commandResponse.getResponse().getContentAsByteArray());
        assertThat(commandResult.path("status").asText()).isEqualTo("ACCEPTED");
        long resultRevision = commandResult.path("resultRevision").asLong();
        assertThat(resultRevision).isEqualTo(2L);
        String authorUserId = userIdFor(actor).toString();

        // These operation.applied assertions fail for both recipients if accepted-command publishing is removed.
        assertOperationApplied(actorStream, operationId, resultRevision, authorUserId);
        assertOperationApplied(otherMemberStream, operationId, resultRevision, authorUserId);
    }

    @Test
    void acceptedCommandWithNullTargetFieldsStillReachesSubscriber() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID modelId = modelId(owner, projectId);
        MvcResult stream = openStream(owner, projectId);
        UUID operationId = UUID.randomUUID();
        Map<String, Object> target = new java.util.LinkedHashMap<>();
        target.put("scope", null);
        target.put("id", null);

        MvcResult commandResponse = mockMvc.perform(authorizedAs(owner,
                post("/projects/{projectId}/commands", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(commandEnvelope(operationId, projectId, modelId, 1L, List.of(target)))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode commandResult = objectMapper.readTree(commandResponse.getResponse().getContentAsByteArray());
        assertThat(commandResult.path("status").asText()).isEqualTo("ACCEPTED");
        long resultRevision = commandResult.path("resultRevision").asLong();
        assertThat(resultRevision).isEqualTo(2L);

        assertOperationApplied(stream, operationId, resultRevision, userIdFor(owner).toString());
        String body = stream.getResponse().getContentAsString();
        assertThat(body).contains("\"targets\":[{\"scope\":null,\"id\":null}]");
    }

    @Test
    void rejectedCommandPublishesNoOperationAppliedEvent() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID modelId = modelId(owner, projectId);
        MvcResult stream = openStream(owner, projectId);
        UUID operationId = UUID.randomUUID();

        MvcResult commandResponse = mockMvc.perform(authorizedAs(owner,
                post("/projects/{projectId}/commands", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(commandEnvelope(operationId, projectId, modelId, 0L))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode commandResult = objectMapper.readTree(commandResponse.getResponse().getContentAsByteArray());
        assertThat(commandResult.path("status").asText()).isEqualTo("REJECTED");
        assertThat(commandResult.path("diagnostics").get(0).path("code").asText())
                .isEqualTo("REVISION_MISMATCH");

        // The sentinel is published after the rejected POST. Its receipt proves the stream has advanced past
        // that command, so this is not a race; any wrongly emitted operation.applied event would already be present.
        ProjectEvent sentinel = ProjectEvent.of("test.after-rejection", projectId, Map.of("marker", "after-rejection"));
        eventBus.publish(sentinel);
        String body = stream.getResponse().getContentAsString();
        assertThat(body).contains("event:test.after-rejection");
        // This assertion fails if rejected commands start publishing operation.applied events.
        assertThat(body).doesNotContain("event:operation.applied");
    }

    private UUID createProject(String owner) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "Command events " + UUID.randomUUID())))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("id").asText());
    }

    private UUID modelId(String username, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username, get("/projects/{projectId}/model", projectId)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("id").asText());
    }

    private MvcResult openStream(String username, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(username,
                get("/projects/{id}/events", projectId).accept(MediaType.TEXT_EVENT_STREAM)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        openStreams.add(result);
        return result;
    }

    private byte[] commandEnvelope(UUID operationId, UUID projectId, UUID modelId, long baseRevision)
            throws Exception {
        return commandEnvelope(operationId, projectId, modelId, baseRevision, List.of());
    }

    private byte[] commandEnvelope(UUID operationId, UUID projectId, UUID modelId, long baseRevision,
            List<?> targets) throws Exception {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("operationId", operationId);
        envelope.put("type", "classifier.create");
        envelope.put("projectId", projectId);
        envelope.put("modelId", modelId);
        envelope.put("baseRevision", baseRevision);
        envelope.put("targets", targets);
        envelope.put("preconditions", List.of());
        envelope.put("payload", Map.of("id", UUID.randomUUID(), "name", "DeliveredClass", "kind", "CLASS"));
        envelope.put("origin", "MANUAL");
        envelope.put("idempotencyKey", UUID.randomUUID().toString());
        return objectMapper.writeValueAsBytes(envelope);
    }

    private static void assertOperationApplied(MvcResult result, UUID operationId, long resultRevision,
            String authorUserId) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("event:operation.applied")
                .contains("\"operationId\":\"" + operationId + "\"")
                .contains("\"resultRevision\":" + resultRevision)
                .contains("\"author\":\"" + authorUserId + "\"");
    }

    private String username(String role) {
        return "command-event-" + role + "-" + UUID.randomUUID();
    }
}
