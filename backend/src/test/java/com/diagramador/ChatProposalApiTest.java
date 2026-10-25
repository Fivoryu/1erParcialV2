package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.assistant.LlamaCppAssistantProvider;
import com.diagramador.domain.ProjectMemberRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Chat suggestions (S6 of odd/tasks/versionado-seguimiento-ia.md): the assistant proposes a plan as
 * a card every member sees, and only an editor can accept it, which applies it through the normal
 * command path. A proposal changes nothing until it is accepted.
 */
@SpringBootTest(properties = "diagrammer.assistant.enabled=true")
@AutoConfigureMockMvc
class ChatProposalApiTest extends AuthenticatedApiTest {

    private static final String CREATE_PATIENT_PLAN = "```json\n{\"intent\":\"plan\",\"operations\":"
            + "[{\"type\":\"classifier.create\",\"targetName\":\"Paciente\"}],\"question\":null,\"explanation\":\"\"}\n```";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private LlamaCppAssistantProvider provider;

    @Test
    void aProposalIsSharedWithEveryMemberAndChangesNothingUntilAccepted() throws Exception {
        String owner = username("owner");
        String viewer = username("viewer");
        UUID projectId = createProject(owner);
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);
        UUID sessionId = createSession(owner, projectId);
        when(provider.draft(anyString(), anyString())).thenReturn(CREATE_PATIENT_PLAN);

        JsonNode proposal = propose(owner, projectId, sessionId, "Agregá la clase Paciente");

        assertThat(proposal.path("status").asText()).isEqualTo("PENDING");
        assertThat(proposal.path("operations")).hasSize(1);
        assertThat(proposal.path("operations").get(0).path("type").asText()).isEqualTo("classifier.create");
        assertThat(proposal.path("requestedByDisplayName").asText()).isNotBlank();

        JsonNode asSeenByViewer = json(mockMvc.perform(authorizedAs(viewer,
                        get("/projects/{p}/sessions/{s}/proposals", projectId, sessionId)))
                .andExpect(status().isOk()).andReturn());
        assertThat(asSeenByViewer).hasSize(1);
        assertThat(asSeenByViewer.get(0).path("id").asText()).isEqualTo(proposal.path("id").asText());

        JsonNode history = json(mockMvc.perform(authorizedAs(viewer,
                        get("/projects/{p}/sessions/{s}/messages", projectId, sessionId)))
                .andExpect(status().isOk()).andReturn());
        assertThat(history.findValuesAsText("kind")).contains("PROPOSAL");
        assertThat(classifierNames(owner, projectId)).doesNotContain("Paciente");
    }

    @Test
    void acceptingAppliesThePlanAndRecordsWhoDecidedInTheChat() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID sessionId = createSession(owner, projectId);
        when(provider.draft(anyString(), anyString())).thenReturn(CREATE_PATIENT_PLAN);
        JsonNode proposal = propose(owner, projectId, sessionId, "Agregá la clase Paciente");

        JsonNode decided = json(mockMvc.perform(authorizedAs(owner,
                        post("/projects/{p}/sessions/{s}/proposals/{id}/accept", projectId, sessionId,
                                proposal.path("id").asText())))
                .andExpect(status().isOk()).andReturn());

        assertThat(decided.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(decided.path("decidedByUserId").asText()).isEqualTo(userIdFor(owner).toString());
        assertThat(decided.path("resultSummary").asText()).startsWith("1 of 1 change applied");
        assertThat(classifierNames(owner, projectId)).contains("Paciente");
        JsonNode history = json(mockMvc.perform(authorizedAs(owner,
                        get("/projects/{p}/sessions/{s}/messages", projectId, sessionId)))
                .andExpect(status().isOk()).andReturn());
        assertThat(history.findValuesAsText("body")).anyMatch(body -> body.contains("the suggestion: 1 of 1"));
    }

    @Test
    void rejectingKeepsTheDiagramAndADecidedProposalCannotBeDecidedAgain() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID sessionId = createSession(owner, projectId);
        when(provider.draft(anyString(), anyString())).thenReturn(CREATE_PATIENT_PLAN);
        String id = propose(owner, projectId, sessionId, "Agregá la clase Paciente").path("id").asText();

        JsonNode rejected = json(mockMvc.perform(authorizedAs(owner,
                        post("/projects/{p}/sessions/{s}/proposals/{id}/reject", projectId, sessionId, id)))
                .andExpect(status().isOk()).andReturn());
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(classifierNames(owner, projectId)).doesNotContain("Paciente");

        MvcResult again = mockMvc.perform(authorizedAs(owner,
                        post("/projects/{p}/sessions/{s}/proposals/{id}/accept", projectId, sessionId, id)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json(again).path("code").asText()).isEqualTo("PROPOSAL_DECIDED");
        assertThat(classifierNames(owner, projectId)).doesNotContain("Paciente");
    }

    @Test
    void aViewerCanReadButNeitherSuggestNorDecide() throws Exception {
        String owner = username("owner");
        String viewer = username("viewer");
        UUID projectId = createProject(owner);
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);
        UUID sessionId = createSession(owner, projectId);
        when(provider.draft(anyString(), anyString())).thenReturn(CREATE_PATIENT_PLAN);
        String id = propose(owner, projectId, sessionId, "Agregá la clase Paciente").path("id").asText();

        mockMvc.perform(authorizedAs(viewer, post("/projects/{p}/sessions/{s}/proposals", projectId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("utterance", "otra clase")))))
                .andExpect(status().isForbidden());
        mockMvc.perform(authorizedAs(viewer,
                        post("/projects/{p}/sessions/{s}/proposals/{id}/accept", projectId, sessionId, id)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authorizedAs(viewer,
                        post("/projects/{p}/sessions/{s}/proposals/{id}/reject", projectId, sessionId, id)))
                .andExpect(status().isForbidden());
        assertThat(classifierNames(owner, projectId)).doesNotContain("Paciente");
    }

    @Test
    void aRequestWithoutAUsablePlanIsExplainedAndStoresNothing() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner);
        UUID sessionId = createSession(owner, projectId);
        when(provider.draft(anyString(), anyString())).thenReturn("No puedo formar un plan válido.");

        MvcResult result = mockMvc.perform(authorizedAs(owner,
                        post("/projects/{p}/sessions/{s}/proposals", projectId, sessionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("utterance", "hacé algo")))))
                .andExpect(status().isUnprocessableEntity()).andReturn();

        assertThat(json(result).path("code").asText()).isEqualTo("NO_PROPOSAL");
        Integer stored = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM diagrammer.chat_proposal WHERE session_id = ?::uuid", Integer.class,
                sessionId.toString());
        assertThat(stored).isZero();
    }

    private JsonNode propose(String username, UUID projectId, UUID sessionId, String utterance) throws Exception {
        return json(mockMvc.perform(authorizedAs(username,
                        post("/projects/{p}/sessions/{s}/proposals", projectId, sessionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("utterance", utterance)))))
                .andExpect(status().isCreated()).andReturn());
    }

    private java.util.List<String> classifierNames(String username, UUID projectId) throws Exception {
        JsonNode model = json(mockMvc.perform(authorizedAs(username, get("/projects/{p}/model", projectId)))
                .andExpect(status().isOk()).andReturn());
        return model.path("classifiers").findValuesAsText("name");
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String username(String role) {
        return "cp-" + role + "-" + UUID.randomUUID();
    }

    private UUID createProject(String owner) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("name", "Proposals " + UUID.randomUUID())))))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json(result).path("id").asText());
    }

    private UUID createSession(String username, UUID projectId) throws Exception {
        UUID viewId = jdbcTemplate.queryForObject(
                "SELECT dv.view_id FROM diagrammer.diagram_view dv "
                        + "JOIN diagrammer.uml_model m ON m.model_id = dv.model_id "
                        + "WHERE m.project_id = ?::uuid AND dv.is_default LIMIT 1",
                UUID.class, projectId.toString());
        MvcResult result = mockMvc.perform(authorizedAs(username,
                        post("/projects/{p}/views/{v}/sessions", projectId, viewId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("title", "Suggestions")))))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json(result).path("id").asText());
    }
}
