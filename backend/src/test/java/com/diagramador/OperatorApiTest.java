package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class OperatorApiTest extends AuthenticatedApiTest {

    private static final String OPERATOR_TOKEN = UUID.randomUUID().toString();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void operatorProperties(DynamicPropertyRegistry registry) {
        registry.add("diagrammer.operator.token", () -> OPERATOR_TOKEN);
    }

    @Test
    void operatorCanReachEveryAllowListedEndpoint() throws Exception {
        String projectId = createProject("operator-visible");

        MvcResult projectsResult = mockMvc.perform(withOperator(get("/projects")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode projects = objectMapper.readTree(projectsResult.getResponse().getContentAsByteArray());
        assertThat(projects.findValuesAsText("id")).contains(projectId);

        mockMvc.perform(withOperator(get("/projects/{projectId}/deployments", projectId)))
                .andExpect(status().isOk());
        mockMvc.perform(withOperator(get("/projects/{projectId}/generation-targets", projectId)))
                .andExpect(status().isOk());

        mockMvc.perform(withOperator(get("/projects/{projectId}/generations/{runId}/artifacts", projectId,
                UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));

        UUID deploymentId = UUID.randomUUID();
        mockMvc.perform(withOperator(post("/deployments/{deploymentId}/claim", deploymentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("operatorId", "test-operator")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_NOT_FOUND"));
        mockMvc.perform(withOperator(post("/deployments/{deploymentId}/report", deploymentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("outcome", "STOPPED")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_NOT_FOUND"));
        mockMvc.perform(withOperator(post("/deployments/{deploymentId}/stop", deploymentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_NOT_FOUND"));
    }

    @Test
    void operatorIsForbiddenFromUserOnlyProjectOperations() throws Exception {
        String projectId = createProject("operator-denied");

        mockMvc.perform(withOperator(post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "forbidden", "description", "")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(withOperator(post("/projects/{projectId}/commands", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(withOperator(get("/projects/{projectId}/model", projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void userBearerRetainsMembershipFilteredProjectList() throws Exception {
        String projectId = createProject("user-owned");
        String otherUserToken = bearer("regular-" + UUID.randomUUID());

        MvcResult result = mockMvc.perform(get("/projects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherUserToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode projects = objectMapper.readTree(result.getResponse().getContentAsByteArray());

        assertThat(projects.findValuesAsText("id")).doesNotContain(projectId);
    }

    private String createProject(String suffix) throws Exception {
        MvcResult result = mockMvc.perform(authorized(post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", suffix, "description", "operator test")))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("id").asText();
    }

    private MockHttpServletRequestBuilder withOperator(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR_TOKEN);
    }
}

@SpringBootTest(properties = "diagrammer.operator.token=")
@AutoConfigureMockMvc
class OperatorApiNoTokenTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void bearerIsUnauthenticatedWhenOperatorTokenIsNotConfigured() throws Exception {
        mockMvc.perform(get("/projects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
