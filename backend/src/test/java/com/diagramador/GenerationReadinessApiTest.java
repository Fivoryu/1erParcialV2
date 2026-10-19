package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.diagramador.domain.ModelVersion;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.persistence.ModelVersionRepository;
import java.util.ArrayList;
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

@SpringBootTest
@AutoConfigureMockMvc
class GenerationReadinessApiTest extends AuthenticatedApiTest {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final ModelVersionRepository versionRepository;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    GenerationReadinessApiTest(MockMvc mockMvc, ObjectMapper objectMapper,
            ModelVersionRepository versionRepository, JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.versionRepository = versionRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void cleanCurrentVersionIsReadyWithTheExactResponseContract() throws Exception {
        ReadinessFixture fixture = readinessFixture("ready");

        JsonNode response = getReadiness(fixture.projectId(), fixture.version().getId());

        assertThat(fieldNames(response)).containsExactlyInAnyOrder("versionId", "profile", "ready", "diagnostics");
        assertThat(response.path("versionId").asText()).isEqualTo(fixture.version().getId().toString());
        assertThat(response.path("profile").asText()).isEqualTo("Spring Boot PostgreSQL V1");
        assertThat(response.path("ready").asBoolean()).isTrue();
        assertThat(response.path("diagnostics").isArray()).isTrue();
        assertThat(response.path("diagnostics")).isEmpty();
    }

    @Test
    void unsupportedGeneralizationBlocksReadinessWithMapperDiagnostic() throws Exception {
        ProjectFixture project = projectFixture("unsupported-feature");
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        long revision = createClassifier(project, project.revision(), parentId, "Parent");
        revision = createClassifier(project, revision, childId, "Child");
        UUID relationshipId = UUID.randomUUID();
        createRelationship(project, revision, relationshipId, "GENERALIZATION", childId, parentId);
        ModelVersion version = currentVersion(project.modelId());

        JsonNode response = getReadiness(project.projectId(), version.getId());
        JsonNode diagnostic = findDiagnostic(response, "UNSUPPORTED_UML_FEATURE");

        assertThat(response.path("ready").asBoolean()).isFalse();
        assertThat(diagnostic).isNotNull();
        assertThat(fieldNames(diagnostic)).containsExactlyInAnyOrder(
                "code", "severity", "elementId", "feature", "message", "blocking");
        assertThat(diagnostic.path("severity").asText()).isEqualTo("ERROR");
        assertThat(diagnostic.path("elementId").asText()).isEqualTo(relationshipId.toString());
        assertThat(diagnostic.path("feature").asText()).isEqualTo("GENERALIZATION");
        assertThat(diagnostic.path("message").asText())
                .isEqualTo("The Increment 2 subset does not map relationship feature 'GENERALIZATION'.");
        assertThat(diagnostic.path("blocking").asBoolean()).isTrue();
    }

    @Test
    void readinessDoesNotPersistRunsVerificationResultsProfilesOrTemplatesOrChangeTheModel() throws Exception {
        ReadinessFixture fixture = readinessFixture("no-writes");
        long runsBefore = countRuns(fixture.modelId());
        long verificationsBefore = countVerificationResults(fixture.modelId());
        long profilesBefore = countProfiles(fixture.projectId());
        long templatesBefore = countTemplates(fixture.projectId());

        JsonNode response = getReadiness(fixture.projectId(), fixture.version().getId());
        JsonNode modelAfter = getJson("/projects/{projectId}/model", fixture.projectId());

        assertThat(response.path("ready").asBoolean()).isTrue();
        assertThat(countRuns(fixture.modelId())).isEqualTo(runsBefore).isZero();
        assertThat(countVerificationResults(fixture.modelId())).isEqualTo(verificationsBefore).isZero();
        assertThat(countProfiles(fixture.projectId())).isEqualTo(profilesBefore).isZero();
        assertThat(countTemplates(fixture.projectId())).isEqualTo(templatesBefore).isZero();
        assertThat(modelAfter.path("revision").asLong()).isEqualTo(fixture.revision());
        assertThat(modelAfter.path("versionId").asText()).isEqualTo(fixture.version().getId().toString());
    }

    @Test
    void viewerCanReadButNonMemberCannotAndForeignOrUnknownVersionsAreNotFound() throws Exception {
        ReadinessFixture fixture = readinessFixture("access-control");
        String viewer = "readiness-viewer-" + UUID.randomUUID();
        bearer(viewer);
        addMembership(viewer, fixture.projectId(), ProjectMemberRole.VIEWER);

        mockMvc.perform(authorizedAs(viewer, get("/projects/{projectId}/generation-readiness", fixture.projectId())
                .param("versionId", fixture.version().getId().toString())
                .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk());

        String nonMember = "readiness-non-member-" + UUID.randomUUID();
        mockMvc.perform(authorizedAs(nonMember, get("/projects/{projectId}/generation-readiness", fixture.projectId())
                .param("versionId", fixture.version().getId().toString())
                .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isForbidden());

        ReadinessFixture otherProject = readinessFixture("foreign-version");
        mockMvc.perform(authorized(get("/projects/{projectId}/generation-readiness", fixture.projectId())
                .param("versionId", otherProject.version().getId().toString())
                .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isNotFound());
        mockMvc.perform(authorized(get("/projects/{projectId}/generation-readiness", fixture.projectId())
                .param("versionId", UUID.randomUUID().toString())
                .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isNotFound());
        mockMvc.perform(authorized(get("/projects/{projectId}/generation-readiness", UUID.randomUUID())
                .param("versionId", fixture.version().getId().toString())
                .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isNotFound());
        mockMvc.perform(authorized(get("/projects/{projectId}/generation-readiness", fixture.projectId())
                .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authorized(get("/projects/{projectId}/generation-readiness", fixture.projectId())
                .param("versionId", "not-a-uuid")
                .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isBadRequest());
    }

    private ReadinessFixture readinessFixture(String label) throws Exception {
        ProjectFixture project = projectFixture(label);
        long revision = createClassifier(project, project.revision(), UUID.randomUUID(), "Customer");
        ModelVersion version = currentVersion(project.modelId());
        return new ReadinessFixture(project.projectId(), project.modelId(), revision, version);
    }

    private ProjectFixture projectFixture(String label) throws Exception {
        JsonNode project = objectMapper.readTree(mockMvc.perform(authorized(post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "Generation readiness " + label + " "
                        + UUID.randomUUID())))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());
        UUID projectId = UUID.fromString(project.path("id").asText());
        JsonNode model = getJson("/projects/{projectId}/model", projectId);
        return new ProjectFixture(projectId, UUID.fromString(model.path("id").asText()),
                model.path("revision").asLong());
    }

    private long createClassifier(ProjectFixture project, long baseRevision, UUID classifierId, String name)
            throws Exception {
        JsonNode result = postCommand(project, baseRevision, "classifier.create", List.of(),
                Map.of("id", classifierId, "name", name, "kind", "CLASS"));
        return result.path("resultRevision").asLong();
    }

    private void createRelationship(ProjectFixture project, long baseRevision, UUID relationshipId, String kind,
            UUID sourceId, UUID targetId) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", relationshipId);
        payload.put("kind", kind);
        payload.put("source", Map.of("classifierId", sourceId));
        payload.put("target", Map.of("classifierId", targetId));
        postCommand(project, baseRevision, "relationship.create", List.of(
                Map.of("scope", "classifier", "id", sourceId),
                Map.of("scope", "classifier", "id", targetId)), payload);
    }

    private JsonNode postCommand(ProjectFixture project, long baseRevision, String type,
            List<Map<String, Object>> targets, Map<String, Object> payload) throws Exception {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("operationId", UUID.randomUUID());
        envelope.put("type", type);
        envelope.put("projectId", project.projectId());
        envelope.put("modelId", project.modelId());
        envelope.put("baseRevision", baseRevision);
        envelope.put("targets", targets);
        envelope.put("preconditions", List.of());
        envelope.put("payload", payload);
        envelope.put("origin", "MANUAL");
        envelope.put("idempotencyKey", UUID.randomUUID().toString());
        JsonNode result = objectMapper.readTree(mockMvc.perform(authorized(post("/projects/{projectId}/commands",
                project.projectId()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(envelope))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(result.path("status").asText()).isEqualTo("ACCEPTED");
        return result;
    }

    private ModelVersion currentVersion(UUID modelId) {
        return versionRepository.findTopByModelIdOrderByRevisionDesc(modelId).orElseThrow();
    }

    private JsonNode getReadiness(UUID projectId, UUID versionId) throws Exception {
        return objectMapper.readTree(mockMvc.perform(authorized(get(
                "/projects/{projectId}/generation-readiness", projectId)
                .param("versionId", versionId.toString())
                .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private JsonNode getJson(String path, Object... variables) throws Exception {
        return objectMapper.readTree(mockMvc.perform(authorized(get(path, variables)
                .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private long countRuns(UUID modelId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM diagrammer.generation_run WHERE model_id = ?", Long.class, modelId);
    }

    private long countVerificationResults(UUID modelId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM diagrammer.verification_result result
                JOIN diagrammer.generation_run run ON run.run_id = result.run_id
                WHERE run.model_id = ?
                """, Long.class, modelId);
    }

    private long countProfiles(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM diagrammer.generation_profile WHERE project_id = ?", Long.class, projectId);
    }

    private long countTemplates(UUID projectId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM diagrammer.template_version template
                JOIN diagrammer.generation_profile profile ON profile.profile_id = template.profile_id
                WHERE profile.project_id = ?
                """, Long.class, projectId);
    }

    private JsonNode findDiagnostic(JsonNode response, String code) {
        for (JsonNode diagnostic : response.path("diagnostics")) {
            if (code.equals(diagnostic.path("code").asText())) {
                return diagnostic;
            }
        }
        return null;
    }

    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private record ProjectFixture(UUID projectId, UUID modelId, long revision) {
    }

    private record ReadinessFixture(UUID projectId, UUID modelId, long revision, ModelVersion version) {
    }
}
