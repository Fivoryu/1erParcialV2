package com.diagramador;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.domain.ProjectMemberRole;
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

/**
 * T10 accumulated-verification fix (L54): the generic /commands endpoint must not be a side door
 * for the XMI REPLACE mode — destroying views cascades chat history and memory away, so the
 * OWNER-only rule of the import route (L36) applies to every route into the same handler.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class XmiCommandsOwnerBypassTest extends AuthenticatedApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void replacingThroughTheCommandsEndpointIsOwnerOnly() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID modelId = modelId(projectId);
        String editor = username("editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("operationId", UUID.randomUUID());
        envelope.put("type", "xmi.import");
        envelope.put("projectId", projectId);
        envelope.put("modelId", modelId);
        envelope.put("baseRevision", 1L);
        envelope.put("targets", List.of());
        envelope.put("preconditions", List.of());
        envelope.put("payload", Map.of("mode", "REEMPLAZAR", "fileBase64", "x", "checksum", "0".repeat(64),
                "idempotencyKey", UUID.randomUUID().toString()));
        envelope.put("origin", "IMPORT");
        envelope.put("idempotencyKey", UUID.randomUUID().toString());

        mockMvc.perform(authorizedAs(editor, post("/projects/{projectId}/commands", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(envelope))))
                .andExpect(status().isForbidden());

        // The OWNER keeps the generic route for the same command.
        mockMvc.perform(authorizedAs(owner, post("/projects/{projectId}/commands", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(envelope))))
                .andExpect(result -> {
                    // The OWNER passes the authorization gate: any status except 403 is fine here
                    // (a 400 for the fake payload is expected — the point is not being forbidden).
                    int status = result.getResponse().getStatus();
                    if (status == 403) {
                        throw new AssertionError("The OWNER was forbidden on their own project.");
                    }
                });
    }

    private String username(String role) {
        // Auth usernames are capped at 64 characters; a short prefix keeps room for the UUID suffix.
        return "xb-" + role + "-" + UUID.randomUUID();
    }

    private UUID createProject(String owner) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Bypass " + UUID.randomUUID())))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText());
    }

    private UUID modelId(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT model_id FROM diagrammer.uml_model WHERE project_id = ?::uuid LIMIT 1",
                UUID.class, projectId.toString());
    }
}
