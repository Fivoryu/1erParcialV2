package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.api.ApiException;
import com.diagramador.identity.AuthenticationInterceptor;
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
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
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

    @Autowired
    private AuthenticationInterceptor authenticationInterceptor;

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
    void operatorCanUseVcsRoutesButRemainsForbiddenFromProjectAndModelOperations() throws Exception {
        String projectId = createProject("operator-vcs");
        mockMvc.perform(authorized(get("/projects/{projectId}/vcs/status", projectId)))
                .andExpect(status().isOk());
        MvcResult modelResult = mockMvc.perform(authorized(get("/projects/{projectId}/model", projectId)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode model = objectMapper.readTree(modelResult.getResponse().getContentAsByteArray());
        UUID modelId = UUID.fromString(model.path("id").asText());

        Map<String, Object> envelope = Map.of(
                "operationId", UUID.randomUUID(),
                "type", "classifier.create",
                "projectId", UUID.fromString(projectId),
                "modelId", modelId,
                "baseRevision", model.path("revision").asLong(),
                "targets", java.util.List.of(),
                "preconditions", java.util.List.of(),
                "payload", Map.of("id", UUID.randomUUID(), "name", "Owned", "kind", "CLASS"),
                "origin", "MANUAL",
                "idempotencyKey", UUID.randomUUID().toString());
        mockMvc.perform(authorized(post("/projects/{projectId}/commands", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(envelope))))
                .andExpect(status().isOk());

        mockMvc.perform(withOperator(get("/projects/{projectId}/vcs/status", projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branch").value("main"))
                .andExpect(jsonPath("$.dirty").value(true));
        mockMvc.perform(withOperator(post("/projects/{projectId}/vcs/commits", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("message", "Operator commit")))))
                .andExpect(status().isCreated());
        mockMvc.perform(withOperator(post("/projects/{projectId}/vcs/branches", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "operator-branch")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("operator-branch"));
        mockMvc.perform(withOperator(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/projects/{projectId}/vcs/branches/operator-branch", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("isProtected", true)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isProtected").value(true));

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
        mockMvc.perform(withOperator(get("/projects/not-a-uuid/vcs/status")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void operatorAllowlistRejectsTraversalAndKeepsUserOnlyRoutesForbidden() throws Exception {
        String projectId = createProject("operator-traversal");

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

        for (String traversal : new String[] {"..", "%2e%2e", "%2E"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST",
                    "/projects/" + projectId + "/vcs/" + traversal + "/commands");
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR_TOKEN);
            assertThatThrownBy(() -> authenticationInterceptor.preHandle(request,
                    new MockHttpServletResponse(), new Object()))
                    .isInstanceOfSatisfying(ApiException.class, exception -> {
                        assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(exception.code()).isEqualTo("FORBIDDEN");
                    });
        }
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
