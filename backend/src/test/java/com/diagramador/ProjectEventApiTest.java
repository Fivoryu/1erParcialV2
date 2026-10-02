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
class ProjectEventApiTest extends AuthenticatedApiTest {

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
    void activeProjectMemberReceivesPublishedEventOnTwoStreams() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String member = username("member");
        bearer(member);
        addMembership(member, projectId, ProjectMemberRole.VIEWER);
        int subscribersBeforeRequest = eventBus.subscriberCount(projectId);

        MvcResult first = mockMvc.perform(authorizedAs(member,
                get("/projects/{id}/events", projectId).accept(MediaType.TEXT_EVENT_STREAM)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult second = mockMvc.perform(authorizedAs(member,
                get("/projects/{id}/events", projectId).accept(MediaType.TEXT_EVENT_STREAM)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        openStreams.add(first);
        openStreams.add(second);

        assertThat(subscribersBeforeRequest).isZero();
        assertThat(eventBus.subscriberCount(projectId)).isEqualTo(subscribersBeforeRequest + 2);

        ProjectEvent event = ProjectEvent.of("test.connected", projectId, Map.of("operationId", "op-123"));
        eventBus.publish(event);
        assertThat(first.getResponse().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertEventReceived(first, event);
        assertEventReceived(second, event);
    }

    @Test
    void authenticatedNonMemberIsForbiddenWithoutCreatingAnEmitter() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        String outsider = username("outsider");
        bearer(outsider);
        int subscribersBeforeRequest = eventBus.subscriberCount(projectId);

        mockMvc.perform(authorizedAs(outsider, get("/projects/{id}/events", projectId)))
                .andExpect(status().isForbidden());

        assertThat(eventBus.subscriberCount(projectId)).isEqualTo(subscribersBeforeRequest);
    }

    @Test
    void eventStreamRequiresABearerToken() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        int subscribersBeforeRequest = eventBus.subscriberCount(projectId);

        mockMvc.perform(get("/projects/{id}/events", projectId))
                .andExpect(status().isUnauthorized());

        assertThat(eventBus.subscriberCount(projectId)).isEqualTo(subscribersBeforeRequest);
    }

    private UUID createProject(String owner) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "Event channel " + UUID.randomUUID())))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode project = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return UUID.fromString(project.path("id").asText());
    }

    private static void assertEventReceived(MvcResult result, ProjectEvent event) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("event:" + event.type())
                .contains("\"schemaVersion\":\"1.0\"")
                .contains("\"type\":\"" + event.type() + "\"")
                .contains("\"projectId\":\"" + event.projectId() + "\"")
                .contains("\"payload\":{\"operationId\":\"op-123\"}");
    }

    private String username(String role) {
        return "event-" + role + "-" + UUID.randomUUID();
    }
}
