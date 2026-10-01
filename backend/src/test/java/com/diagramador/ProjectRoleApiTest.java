package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.api.ApiException;
import com.diagramador.domain.GenerationProfile;
import com.diagramador.domain.GenerationTarget;
import com.diagramador.domain.ModelVersion;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.TemplateVersion;
import com.diagramador.generation.GenerationArtifactService;
import com.diagramador.generation.GenerationIdentityService;
import com.diagramador.generation.GenerationIdentityService.GenerationRunStart;
import com.diagramador.generation.GenerationIdentityService.TargetConfiguration;
import com.diagramador.generation.InputSnapshotFactory;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.ModelVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectRoleApiTest extends AuthenticatedApiTest {

    private static final String OPERATOR_TOKEN = UUID.randomUUID().toString();

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final GenerationIdentityService identityService;
    private final GenerationArtifactService artifactService;
    private final InputSnapshotFactory snapshotFactory;
    private final ModelVersionRepository versionRepository;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final CurrentUserContext currentUserContext;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    ProjectRoleApiTest(MockMvc mockMvc, ObjectMapper objectMapper, GenerationIdentityService identityService,
            GenerationArtifactService artifactService, InputSnapshotFactory snapshotFactory,
            ModelVersionRepository versionRepository, ProjectAuthorizationService projectAuthorizationService,
            CurrentUserContext currentUserContext,
            JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.identityService = identityService;
        this.artifactService = artifactService;
        this.snapshotFactory = snapshotFactory;
        this.versionRepository = versionRepository;
        this.projectAuthorizationService = projectAuthorizationService;
        this.currentUserContext = currentUserContext;
        this.jdbcTemplate = jdbcTemplate;
    }

    @DynamicPropertySource
    static void operatorProperties(DynamicPropertyRegistry registry) {
        registry.add("diagrammer.operator.token", () -> OPERATOR_TOKEN);
    }

    @AfterEach
    void releaseActiveDeployments() {
        jdbcTemplate.update("UPDATE diagrammer.deployment SET state = 'STOPPED', updated_at = now() "
                + "WHERE state <> 'STOPPED'");
        currentUserContext.clear();
    }

    @Test
    void viewerCanReadTheModelAndTargetsButCannotCreateTargetsOrGenerations() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner, "viewer-read-write");
        UUID modelId = modelId(owner, projectId);
        String viewer = username("viewer");
        bearer(viewer);
        addMembership(viewer, projectId, ProjectMemberRole.VIEWER);

        mockMvc.perform(authorizedAs(viewer, get("/projects/{projectId}/model", projectId)))
                .andExpect(status().isOk());
        mockMvc.perform(authorizedAs(viewer, get("/projects/{projectId}/generation-targets", projectId)))
                .andExpect(status().isOk());
        mockMvc.perform(authorizedAs(viewer, post("/projects/{projectId}/generation-targets", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(targetRequest("viewer-target")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        Map<String, Object> generationRequest = Map.of(
                "modelId", modelId,
                "versionId", UUID.randomUUID(),
                "targetId", UUID.randomUUID(),
                "profileId", UUID.randomUUID(),
                "templateId", UUID.randomUUID(),
                "idempotencyKey", "viewer-generation-" + UUID.randomUUID());
        mockMvc.perform(authorizedAs(viewer, post("/projects/{projectId}/generations", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(generationRequest))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void editorCanCreateAGenerationTarget() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner, "editor-create-target");
        String editor = username("editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);

        MvcResult result = mockMvc.perform(authorizedAs(editor,
                post("/projects/{projectId}/generation-targets", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(targetRequest("editor-target")))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode outcome = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(outcome.path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(outcome.path("code").asText()).isEqualTo("TARGET_CREATED");
    }

    @Test
    void nonMembersCannotReadTargetsGenerationsOrDeployments() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner, "non-member-read");
        String nonMember = username("outsider");
        bearer(nonMember);

        mockMvc.perform(authorizedAs(nonMember, get("/projects/{projectId}/generation-targets", projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(authorizedAs(nonMember,
                get("/projects/{projectId}/generations/{runId}", projectId, UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(authorizedAs(nonMember, get("/projects/{projectId}/deployments", projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void nonMemberCannotStopAnotherProjectsDeployment() throws Exception {
        String owner = username("deployment-owner");
        UUID deploymentId = createPendingDeployment(owner, "non-member-stop");
        String outsider = username("deployment-outsider");
        bearer(outsider);

        // This is a real pending deployment owned by another project. Before the authorization guard,
        // stopDeployment accepted it and transitioned it to STOPPED, so this 403 assertion fails.
        mockMvc.perform(authorizedAs(outsider, post("/deployments/{deploymentId}/stop", deploymentId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void editorOfTheOwningProjectCanStopItsDeployment() throws Exception {
        String owner = username("deployment-owner");
        UUID deploymentId = createPendingDeployment(owner, "editor-stop");
        String editor = username("deployment-editor");
        bearer(editor);
        addMembership(editor, projectIdCreatedBy(owner), ProjectMemberRole.EDITOR);

        mockMvc.perform(authorizedAs(editor, post("/deployments/{deploymentId}/stop", deploymentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STOPPED"));
    }

    @Test
    void operatorCanStopADeployment() throws Exception {
        UUID deploymentId = createPendingDeployment(username("deployment-owner"), "operator-stop");

        mockMvc.perform(withOperator(post("/deployments/{deploymentId}/stop", deploymentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STOPPED"));
    }

    @Test
    void requireOwnerRefusesAnEditor() throws Exception {
        String owner = username("owner");
        UUID projectId = createProject(owner, "owner-capability");
        String editor = username("editor");
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        currentUserContext.set(new CurrentUser(userIdFor(editor), editor, editor));

        assertThatThrownBy(() -> projectAuthorizationService.requireOwner(projectId))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.code()).isEqualTo("FORBIDDEN");
                    assertThat(exception.getMessage())
                            .isEqualTo("This action requires administration of this project.");
                    assertThat(exception.details()).isEmpty();
                });
    }

    private UUID createProject(String owner, String label) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "Project roles " + label)))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID projectId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText());
        assertThat(projectIdCreatedBy(owner)).isEqualTo(projectId);
        return projectId;
    }

    private UUID modelId(String owner, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(authorizedAs(owner, get("/projects/{projectId}/model", projectId)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText());
    }

    private UUID createPendingDeployment(String owner, String label) throws Exception {
        UUID projectId = createProject(owner, label);
        UUID modelId = modelId(owner, projectId);
        createClassifier(owner, projectId, modelId);
        ModelVersion version = versionRepository.findTopByModelIdOrderByRevisionDesc(modelId).orElseThrow();
        GenerationProfile profile = identityService.provisionV1Profile(projectId);
        TemplateVersion template = identityService.provisionV1Template(profile.getId());
        String key = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        GenerationTarget target = identityService.createTarget(modelId, key,
                new TargetConfiguration("Role test target", "POSTGRESQL", "role_test_database", "public", null));
        GenerationRunStart start = identityService.startRun(target.getId(), version.getId(), profile.getId(),
                template.getId());
        artifactService.generate(start.run().getId(), snapshotFactory.create(version));

        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects/{projectId}/deployments", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("runId", start.run().getId())))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("deploymentId").asText());
    }

    private void createClassifier(String owner, UUID projectId, UUID modelId) throws Exception {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("operationId", UUID.randomUUID());
        envelope.put("type", "classifier.create");
        envelope.put("projectId", projectId);
        envelope.put("modelId", modelId);
        envelope.put("baseRevision", 1L);
        envelope.put("targets", List.of());
        envelope.put("preconditions", List.of());
        envelope.put("payload", Map.of("id", UUID.randomUUID(), "name", "Customer", "kind", "CLASS"));
        envelope.put("origin", "MANUAL");
        envelope.put("idempotencyKey", UUID.randomUUID().toString());
        MvcResult result = mockMvc.perform(authorizedAs(owner, post("/projects/{projectId}/commands", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(envelope))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("status").asText())
                .isEqualTo("ACCEPTED");
    }

    private Map<String, Object> targetRequest(String key) {
        return Map.of("targetKey", key + "-" + UUID.randomUUID().toString().substring(0, 8),
                "name", "Target " + key,
                "databaseEngine", "POSTGRESQL",
                "databaseName", "roles_database",
                "databaseSchema", "public");
    }

    private String username(String role) {
        return "role-" + role + "-" + UUID.randomUUID();
    }

    private MockHttpServletRequestBuilder withOperator(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR_TOKEN);
    }
}
