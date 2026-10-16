package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.UmlModel;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.xmi.XmiImportMode;
import com.diagramador.xmi.XmiReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
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
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class XmiImportApiTest extends AuthenticatedApiTest {

    private static final Path FIXTURE = Path.of("..", "docs", "fixtures", "xmi", "Prueba3.xml");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UmlModelRepository modelRepository;
    @Autowired private ClassifierRepository classifierRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void previewReturnsMeasuredCountsAndLossesWithoutPersistingOrChangingTheModel() throws Exception {
        UUID projectId = createProject();
        UUID modelId = modelId(projectId);
        JsonNode before = getJson("/projects/{projectId}/model", projectId);
        long exchangesBefore = exchangeCount(modelId);
        long lossesBefore = lossCount(modelId);
        byte[] bytes = fixtureBytes();

        JsonNode preview = objectMapper.readTree(mockMvc.perform(authorized(post(
                "/projects/{projectId}/xmi/preview", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("fileBase64", Base64.getEncoder()
                        .encodeToString(bytes))))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());

        JsonNode counts = preview.path("counts");
        assertThat(counts.path("classifiers").asInt()).isEqualTo(11);
        assertThat(counts.path("relationships").asInt()).isEqualTo(8);
        assertThat(counts.path("views").asInt()).isEqualTo(1);
        assertThat(counts.path("figurePlacements").asInt()).isEqualTo(10);
        assertThat(counts.path("connectorPlacements").asInt()).isEqualTo(8);
        assertThat(preview.path("losses").findValuesAsText("name"))
                .contains("connector_path_not_interpreted", "classifier_documentation_not_persisted");
        assertThat(preview.path("checksum").asText()).isEqualTo(sha256(bytes));
        assertThat(getJson("/projects/{projectId}/model", projectId)).isEqualTo(before);
        assertThat(exchangeCount(modelId)).isEqualTo(exchangesBefore);
        assertThat(lossCount(modelId)).isEqualTo(lossesBefore);
    }

    @Test
    void importsInReplacementModeAndReturnsTheLossReport() throws Exception {
        UUID projectId = createProject();
        UUID modelId = modelId(projectId);
        seedClassifier(projectId, ClassifierKind.CLASS, "OldClassifier");

        byte[] bytes = fixtureBytes();
        JsonNode response = importFile(projectId, XmiImportMode.REEMPLAZAR, bytes, sha256(bytes));

        assertThat(response.path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(response.at("/command/status").asText()).isEqualTo("ACCEPTED");
        assertThat(response.path("exchangeId").isTextual()).isTrue();
        assertThat(response.path("losses").isArray()).isTrue();
        assertThat(response.path("losses").size()).isPositive();
        JsonNode importedModel = getJson("/projects/{projectId}/model", projectId);
        assertThat(importedModel.path("classifiers")).hasSize(11);
        assertThat(importedModel.path("classifiers").findValuesAsText("name")).doesNotContain("OldClassifier");
        assertThat(exchangeCount(modelId)).isEqualTo(1L);
    }

    @Test
    void importsInAdditionModeAndKeepsExistingClassifiers() throws Exception {
        UUID projectId = createProject();
        UUID modelId = modelId(projectId);
        seedClassifier(projectId, ClassifierKind.CLASS, "ExistingClass");
        byte[] bytes = fixtureBytes();

        JsonNode response = importFile(projectId, XmiImportMode.AGREGAR, bytes, sha256(bytes));

        assertThat(response.path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(response.at("/command/status").asText()).isEqualTo("ACCEPTED");
        JsonNode importedModel = getJson("/projects/{projectId}/model", projectId);
        assertThat(importedModel.path("classifiers")).hasSize(12);
        assertThat(importedModel.path("classifiers").findValuesAsText("name")).contains("ExistingClass");
        assertThat(exchangeCount(modelId)).isEqualTo(1L);
    }

    @Test
    void checksumMismatchReturnsBadRequestWithoutChangingTheProject() throws Exception {
        UUID projectId = createProject();
        UUID modelId = modelId(projectId);
        JsonNode before = getJson("/projects/{projectId}/model", projectId);
        long exchangesBefore = exchangeCount(modelId);
        long lossesBefore = lossCount(modelId);
        byte[] bytes = fixtureBytes();
        MvcResult result = mockMvc.perform(authorized(post("/projects/{projectId}/xmi/import", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(importRequest(bytes, "0".repeat(64),
                        XmiImportMode.REEMPLAZAR)))))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(error.path("code").asText()).isEqualTo("INVALID_PAYLOAD");
        assertThat(error.path("message").asText()).contains("checksum");
        assertThat(getJson("/projects/{projectId}/model", projectId)).isEqualTo(before);
        assertThat(exchangeCount(modelId)).isEqualTo(exchangesBefore);
        assertThat(lossCount(modelId)).isEqualTo(lossesBefore);
    }

    @Test
    void additionCollisionReturnsANameSpecificDiagnosticWithoutChangingTheProject() throws Exception {
        UUID projectId = createProject();
        UUID modelId = modelId(projectId);
        byte[] bytes = fixtureBytes();
        var collision = new XmiReader().read(bytes).model().classifiers().stream()
                .filter(classifier -> classifier.namespaceId() == null).findFirst().orElseThrow();
        seedClassifier(projectId, collision.kind(), collision.name());
        JsonNode before = getJson("/projects/{projectId}/model", projectId);
        long exchangesBefore = exchangeCount(modelId);

        JsonNode response = importFile(projectId, XmiImportMode.AGREGAR, bytes, sha256(bytes));

        assertThat(response.path("outcome").asText()).isEqualTo("REJECTED");
        assertThat(response.at("/command/status").asText()).isEqualTo("REJECTED");
        assertThat(response.at("/command/diagnostics")).hasSize(1);
        assertThat(response.at("/command/diagnostics/0/message").asText()).contains(collision.name());
        assertThat(response.path("exchangeId").isNull()).isTrue();
        assertThat(getJson("/projects/{projectId}/model", projectId)).isEqualTo(before);
        assertThat(exchangeCount(modelId)).isEqualTo(exchangesBefore);
    }

    private UUID createProject() throws Exception {
        MvcResult result = mockMvc.perform(authorized(post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "XMI API " + UUID.randomUUID())))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText());
    }

    private JsonNode getJson(String path, Object... variables) throws Exception {
        MvcResult result = mockMvc.perform(authorized(get(path, variables).accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test
    void replacingTheModelIsReservedToTheOwnerBecauseItDestroysChatHistory() throws Exception {
        // PD-01: only the OWNER may destroy chat history; a REPLACE import cascades
        // view -> chat_session -> diagram_message, so editors must not trigger it (L36).
        UUID projectId = createProject();
        String editor = "xmi-replace-editor-" + UUID.randomUUID();
        bearer(editor);
        addMembership(editor, projectId, ProjectMemberRole.EDITOR);
        byte[] bytes = fixtureBytes();

        mockMvc.perform(authorizedAs(editor, post("/projects/{projectId}/xmi/import", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(importRequest(bytes, sha256(bytes),
                        XmiImportMode.REEMPLAZAR)))))
                .andExpect(status().isForbidden());

        // Lesser import modes stay available to editors.
        importFile(projectId, XmiImportMode.AGREGAR, bytes, sha256(bytes));
    }

    private JsonNode importFile(UUID projectId, XmiImportMode mode, byte[] bytes, String checksum) throws Exception {
        MvcResult result = mockMvc.perform(authorized(post("/projects/{projectId}/xmi/import", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(importRequest(bytes, checksum, mode)))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private Map<String, Object> importRequest(byte[] bytes, String checksum, XmiImportMode mode) {
        return Map.of("fileBase64", Base64.getEncoder().encodeToString(bytes),
                "checksum", checksum,
                "mode", mode,
                "idempotencyKey", "xmi-api-" + UUID.randomUUID());
    }

    private byte[] fixtureBytes() throws Exception {
        return Files.readAllBytes(FIXTURE);
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private UUID modelId(UUID projectId) throws Exception {
        return UUID.fromString(getJson("/projects/{projectId}/model", projectId).path("id").asText());
    }

    private void seedClassifier(UUID projectId, ClassifierKind kind, String name) {
        transactionTemplate.executeWithoutResult(status -> {
            UmlModel model = modelRepository.findByProjectId(projectId).orElseThrow();
            classifierRepository.saveAndFlush(Classifier.create(model, null, kind, name, false));
        });
    }

    private long exchangeCount(UUID modelId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.xmi_exchange WHERE model_id = ?",
                Long.class, modelId);
    }

    private long lossCount(UUID modelId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM diagrammer.xmi_loss_item item "
                + "JOIN diagrammer.xmi_exchange exchange USING (exchange_id) WHERE exchange.model_id = ?",
                Long.class, modelId);
    }

}
