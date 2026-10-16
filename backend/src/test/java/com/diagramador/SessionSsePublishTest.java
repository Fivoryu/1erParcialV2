package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.collaboration.ProjectEvent;
import com.diagramador.collaboration.ProjectEventBus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * T10 accumulated-verification fix (L54): human messages posted to a SESSION must be published
 * over SSE (message.posted) like the legacy route does — collaborators must not need a reload.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionSsePublishTest extends AuthenticatedApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private ProjectEventBus eventBus;

    @Test
    void sessionMessagesArePublishedOverSse() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID viewId = defaultViewId(projectId);
        UUID sessionId = createSession(owner, projectId, viewId, "Sse");

        mockMvc.perform(authorizedAs(owner,
                        post("/projects/{projectId}/sessions/{sessionId}/messages", projectId, sessionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("body", "mensaje por sesión")))))
                .andExpect(status().isCreated());

        ArgumentCaptor<ProjectEvent> events = ArgumentCaptor.forClass(ProjectEvent.class);
        verify(eventBus, timeout(5_000).atLeastOnce()).publish(events.capture());
        assertThat(events.getAllValues().stream()
                .filter(event -> event.projectId().equals(projectId))
                .anyMatch(event -> "message.posted".equals(event.type())
                        && sessionId.equals(event.payload().get("sessionId"))
                        && "HUMAN".equals(event.payload().get("kind")))).isTrue();
    }

    private String username(String role) {
        // Auth usernames are capped at 64 characters; a short prefix keeps room for the UUID suffix.
        return "sp-" + role + "-" + UUID.randomUUID();
    }

    private UUID createProject(String owner) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("name", "Sse " + UUID.randomUUID())))))
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
}
