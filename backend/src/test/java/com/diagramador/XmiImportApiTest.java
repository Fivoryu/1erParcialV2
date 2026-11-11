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

    @Test
    void previewOfANormalFixtureKeepsItsCountsAndFindsNothingToRepairOrBlock() throws Exception {
        UUID projectId = createProject();
        byte[] bytes = fixtureBytes();

        JsonNode preview = preview(projectId, bytes, XmiImportMode.AGREGAR, List.of());

        assertThat(preview.path("counts").path("classifiers").asInt()).isEqualTo(11);
        assertThat(preview.path("counts").path("relationships").asInt()).isEqualTo(8);
        assertThat(preview.path("counts").path("figurePlacements").asInt()).isEqualTo(10);
        assertThat(preview.path("resultingCounts")).isEqualTo(preview.path("counts"));
        assertThat(preview.path("repairs")).isEmpty();
        assertThat(blockers(preview)).isEmpty();
        assertThat(preview.path("clean").asBoolean()).isTrue();
    }

    @Test
    void previewIsADryRunThatReportsACollisionWithItsOptionsAndWritesNothing() throws Exception {
        UUID projectId = createProject();
        UUID modelId = modelId(projectId);
        byte[] bytes = fixtureBytes();
        var collision = firstRootClassifier(bytes);
        seedClassifier(projectId, collision.kind(), collision.name());
        JsonNode before = getJson("/projects/{projectId}/model", projectId);
        long exchangesBefore = exchangeCount(modelId);

        JsonNode preview = preview(projectId, bytes, XmiImportMode.AGREGAR, List.of());

        assertThat(preview.path("clean").asBoolean()).isFalse();
        assertThat(blockers(preview)).singleElement().satisfies(issue -> {
            assertThat(issue.path("code").asText()).isEqualTo("NAME_COLLISION");
            assertThat(issue.path("subject").asText()).isEqualTo(collision.id().toString());
            assertThat(issue.path("message").asText()).contains(collision.name());
            assertThat(issue.path("options")).extracting(JsonNode::asText).containsExactly("RENAME", "SKIP");
        });
        assertThat(getJson("/projects/{projectId}/model", projectId)).isEqualTo(before);
        assertThat(exchangeCount(modelId)).isEqualTo(exchangesBefore);
    }

    @Test
    void previewWithAResolutionShowsTheCollisionResolved() throws Exception {
        UUID projectId = createProject();
        byte[] bytes = fixtureBytes();
        var collision = firstRootClassifier(bytes);
        seedClassifier(projectId, collision.kind(), collision.name());

        JsonNode preview = preview(projectId, bytes, XmiImportMode.AGREGAR, List.of(
                Map.of("subject", collision.id().toString(), "action", "RENAME", "newName", "Otra")));

        assertThat(blockers(preview)).isEmpty();
        assertThat(preview.path("clean").asBoolean()).isTrue();
        assertThat(preview.path("repairs").findValuesAsText("code")).contains("resolution_renamed");
    }

    @Test
    void aCollisionResolvedByRenamingImportsCleanAndKeepsBothClassifiers() throws Exception {
        UUID projectId = createProject();
        byte[] bytes = fixtureBytes();
        var collision = firstRootClassifier(bytes);
        seedClassifier(projectId, collision.kind(), collision.name());

        JsonNode response = importFile(projectId, XmiImportMode.AGREGAR, bytes, sha256(bytes), List.of(
                Map.of("subject", collision.id().toString(), "action", "RENAME", "newName", "Renombrada")));

        assertThat(response.path("outcome").asText()).as("response: %s", response).isEqualTo("ACCEPTED");
        JsonNode model = getJson("/projects/{projectId}/model", projectId);
        assertThat(model.path("classifiers")).hasSize(12);
        assertThat(model.path("classifiers").findValuesAsText("name")).contains(collision.name(), "Renombrada");
    }

    @Test
    void aCollisionResolvedBySkippingImportsTheRestAndRecordsTheLoss() throws Exception {
        UUID projectId = createProject();
        byte[] bytes = fixtureBytes();
        var collision = firstRootClassifier(bytes);
        seedClassifier(projectId, collision.kind(), collision.name());

        JsonNode response = importFile(projectId, XmiImportMode.AGREGAR, bytes, sha256(bytes), List.of(
                Map.of("subject", collision.id().toString(), "action", "SKIP")));

        assertThat(response.path("outcome").asText()).as("response: %s", response).isEqualTo("ACCEPTED");
        assertThat(response.path("losses").findValuesAsText("name")).contains("classifiers_skipped_by_resolution");
        assertThat(getJson("/projects/{projectId}/model", projectId).path("classifiers")).hasSize(11);
    }

    @Test
    void aFileWithSeveralDiagramsImportsItsDefaultDiagramAndNamesTheLoss() throws Exception {
        UUID projectId = createProject();
        byte[] bytes = withSecondDiagram(emit(cleanModel(true)));

        JsonNode preview = preview(projectId, bytes, XmiImportMode.REEMPLAZAR, List.of());
        assertThat(preview.path("counts").path("views").asInt()).isEqualTo(2);
        assertThat(preview.path("resultingCounts").path("views").asInt()).isEqualTo(1);
        assertThat(preview.path("repairs").findValuesAsText("code")).contains("extra_diagrams_not_imported");
        assertThat(preview.path("clean").asBoolean()).isTrue();

        JsonNode response = importFile(projectId, XmiImportMode.REEMPLAZAR, bytes, sha256(bytes), List.of());

        assertThat(response.path("outcome").asText()).as("response: %s", response).isEqualTo("ACCEPTED");
        assertThat(response.path("losses").findValuesAsText("name")).contains("extra_diagrams_not_imported");
        assertThat(getJson("/projects/{projectId}/model", projectId).path("views")).hasSize(1);
    }

    @Test
    void aFileWithoutAnyDiagramGetsTheMandatoryDefaultView() throws Exception {
        UUID projectId = createProject();
        byte[] bytes = emit(cleanModel(false));

        JsonNode response = importFile(projectId, XmiImportMode.REEMPLAZAR, bytes, sha256(bytes), List.of());

        assertThat(response.path("outcome").asText()).as("response: %s", response).isEqualTo("ACCEPTED");
        assertThat(response.path("losses").findValuesAsText("name")).contains("diagram_synthesized");
        JsonNode model = getJson("/projects/{projectId}/model", projectId);
        assertThat(model.path("views")).singleElement().satisfies(view -> {
            assertThat(view.path("is_default").asBoolean()).isTrue();
            assertThat(view.path("element_geometry")).hasSize(2);
        });
    }

    @Test
    void aFileThatWouldLeaveSemanticErrorsBehindIsRefusedBeforeAnyWriteWithASpanishMessage() throws Exception {
        UUID projectId = createProject();
        UUID modelId = modelId(projectId);
        byte[] bytes = emit(modelWithDuplicateAttributeNames());
        JsonNode before = getJson("/projects/{projectId}/model", projectId);
        long exchangesBefore = exchangeCount(modelId);

        JsonNode preview = preview(projectId, bytes, XmiImportMode.REEMPLAZAR, List.of());
        assertThat(preview.path("clean").asBoolean()).isFalse();
        assertThat(blockers(preview)).extracting(issue -> issue.path("code").asText())
                .contains("DUPLICATE_MEMBER_NAME");

        JsonNode response = importFile(projectId, XmiImportMode.REEMPLAZAR, bytes, sha256(bytes), List.of());

        assertThat(response.path("outcome").asText()).isEqualTo("REJECTED");
        assertThat(response.path("exchangeId").isNull()).isTrue();
        assertThat(response.at("/command/diagnostics/0/message").asText())
                .contains("La importación no quedaría limpia", "DUPLICATE_MEMBER_NAME");
        assertThat(getJson("/projects/{projectId}/model", projectId)).isEqualTo(before);
        assertThat(exchangeCount(modelId)).isEqualTo(exchangesBefore);
    }

    private JsonNode preview(UUID projectId, byte[] bytes, XmiImportMode mode, List<Map<String, Object>> resolutions)
            throws Exception {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("fileBase64", Base64.getEncoder().encodeToString(bytes));
        request.put("mode", mode);
        request.put("resolutions", resolutions);
        MvcResult result = mockMvc.perform(authorized(post("/projects/{projectId}/xmi/preview", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private static List<JsonNode> blockers(JsonNode preview) {
        List<JsonNode> result = new java.util.ArrayList<>();
        preview.path("issues").forEach(issue -> {
            if ("BLOCKER".equals(issue.path("severity").asText())) {
                result.add(issue);
            }
        });
        return result;
    }

    private com.diagramador.api.ModelResource.ClassifierResource firstRootClassifier(byte[] bytes) {
        return new XmiReader().read(bytes).model().classifiers().stream()
                .filter(classifier -> classifier.namespaceId() == null).findFirst().orElseThrow();
    }

    private static UUID uid(long value) {
        return UUID.fromString("00000000-0000-0000-0000-%012x".formatted(value));
    }

    private static com.diagramador.api.ModelResource.ClassifierResource clazz(long id, String name,
            List<com.diagramador.api.ModelResource.AttributeResource> attributes) {
        return new com.diagramador.api.ModelResource.ClassifierResource(uid(id), name, ClassifierKind.CLASS, false,
                null, attributes, List.of(), List.of());
    }

    private static com.diagramador.api.ModelResource.AttributeResource stringAttribute(long id, String name) {
        return new com.diagramador.api.ModelResource.AttributeResource(uid(id), name, "ATTRIBUTE", "PUBLIC",
                com.diagramador.domain.MultiplicityKind.EXACTLY_ONE, null, uid(9000), "String", 0);
    }

    private static com.diagramador.api.ModelResource modelOf(boolean withDefaultView,
            List<com.diagramador.api.ModelResource.ClassifierResource> classifiers,
            List<com.diagramador.api.ModelResource.RelationshipResource> relationships) {
        var view = new com.diagramador.api.ModelResource.ViewResource(uid(300), "Importado", withDefaultView,
                classifiers.stream().map(item -> new com.diagramador.api.ModelResource.GeometryResource(
                        uid(500 + item.id().getLeastSignificantBits()), com.diagramador.domain.TargetType.CLASSIFIER,
                        item.id(), 40, 60, 200, 100, 0)).toList(), List.of());
        return new com.diagramador.api.ModelResource(uid(1), uid(2), 3, uid(3), List.of(view), classifiers,
                relationships);
    }

    private static com.diagramador.api.ModelResource cleanModel(boolean withDefaultView) {
        var relationship = new com.diagramador.api.ModelResource.RelationshipResource(uid(910), "ASSOCIATION", "has",
                List.of(new com.diagramador.api.ModelResource.RelationshipEndResource(uid(911), uid(900), null,
                                com.diagramador.domain.MultiplicityKind.EXACTLY_ONE,
                                com.diagramador.domain.NavigationKind.UNSPECIFIED, "none", (short) 1),
                        new com.diagramador.api.ModelResource.RelationshipEndResource(uid(912), uid(901), null,
                                com.diagramador.domain.MultiplicityKind.ZERO_OR_MANY,
                                com.diagramador.domain.NavigationKind.UNSPECIFIED, "none", (short) 2)),
                List.of());
        return modelOf(withDefaultView, List.of(clazz(900, "Owner", List.of(stringAttribute(10, "name"))),
                clazz(901, "Pet", List.of())), List.of(relationship));
    }

    private static com.diagramador.api.ModelResource modelWithDuplicateAttributeNames() {
        return modelOf(true, List.of(clazz(900, "Owner", List.of(stringAttribute(10, "name"),
                stringAttribute(11, "name")))), List.of());
    }

    private static byte[] emit(com.diagramador.api.ModelResource model) {
        return new com.diagramador.xmi.XmiSemanticEmitter().emit(model).document()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Copies the emitted diagram block under another id and name, as a file exported with two diagrams has. */
    private static byte[] withSecondDiagram(byte[] xmi) {
        String text = new String(xmi, java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<diagram [^>]*>.*?</diagram>", java.util.regex.Pattern.DOTALL).matcher(text);
        assertThat(matcher.find()).as("the emitted file carries a diagram").isTrue();
        String block = matcher.group();
        String copy = block.replaceFirst("xmi:id=\"[^\"]*\"",
                "xmi:id=\"" + com.diagramador.xmi.XmiIdCodec.classifierId(uid(301)) + "\"")
                .replace("name=\"Importado\"", "name=\"Segundo\"");
        return (text.substring(0, matcher.end()) + copy + text.substring(matcher.end()))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
        return importFile(projectId, mode, bytes, checksum, List.of());
    }

    private JsonNode importFile(UUID projectId, XmiImportMode mode, byte[] bytes, String checksum,
            List<Map<String, Object>> resolutions) throws Exception {
        Map<String, Object> request = new java.util.LinkedHashMap<>(importRequest(bytes, checksum, mode));
        request.put("resolutions", resolutions);
        MvcResult result = mockMvc.perform(authorized(post("/projects/{projectId}/xmi/import", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request))))
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
