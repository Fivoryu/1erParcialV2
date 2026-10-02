package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.api.ModelResource;
import com.diagramador.api.ModelResource.AttributeResource;
import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.api.ModelResource.RelationshipResource;
import com.diagramador.api.ModelResource.ViewResource;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.ExchangeDirection;
import com.diagramador.domain.ExchangeStatus;
import com.diagramador.domain.SeverityKind;
import com.diagramador.domain.XmiExchange;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.XmiExchangeRepository;
import com.diagramador.persistence.XmiLossItemRepository;
import com.diagramador.xmi.XmiExportService;
import com.diagramador.xmi.XmiReader;
import com.diagramador.xmi.XmiReadingResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

class XmiExportApiTest extends AuthenticatedApiTest {

    private static final Path FIXTURE = Path.of("..", "docs", "fixtures", "xmi", "Prueba3.xml");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UmlModelRepository modelRepository;
    @Autowired private ClassifierRepository classifierRepository;
    @Autowired private XmiExchangeRepository exchangeRepository;
    @Autowired private XmiLossItemRepository lossItemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager entityManager;

    @Test
    void exportsAReaderCompatibleSnapshotAndPersistsItsLossReport() throws Exception {
        UUID projectId = createProject();
        byte[] fixture = Files.readAllBytes(FIXTURE);
        importFixture(projectId, fixture);
        JsonNode sourceJson = getJson("/projects/{projectId}/model", projectId);
        ModelResource source = objectMapper.treeToValue(sourceJson, ModelResource.class);
        byte[] responseBytes = export(projectId);
        JsonNode response = objectMapper.readTree(responseBytes);
        byte[] document = Base64.getDecoder().decode(response.path("fileBase64").asText());
        String checksum = sha256(document);
        UUID exchangeId = UUID.fromString(response.path("exchangeId").asText());
        XmiReadingResult reading = new XmiReader().read(document);

        assertThat(response.path("checksum").asText()).isEqualTo(checksum);
        assertReadBack(source, reading);
        assertThat(response.path("losses").size()).isPositive();

        entityManager.clear();
        XmiExchange persisted = exchangeRepository.findById(exchangeId).orElseThrow();
        var losses = lossItemRepository.findByExchangeId(exchangeId);
        assertThat(persisted.getModel().getId()).isEqualTo(source.id());
        assertThat(persisted.getDirection()).isEqualTo(ExchangeDirection.EXPORT);
        assertThat(persisted.getProfile()).isEqualTo(XmiExportService.PROFILE);
        assertThat(persisted.getModelRevision()).isEqualTo(source.revision());
        assertThat(persisted.getChecksum()).isEqualTo(checksum);
        assertThat(persisted.getArtifactRef()).isNull();
        assertThat(persisted.getStatus()).isEqualTo(ExchangeStatus.PARTIAL);
        assertThat(losses).hasSize(response.path("losses").size()).allSatisfy(loss -> {
            assertThat(loss.getSeverity()).isEqualTo(SeverityKind.WARNING);
            assertThat(loss.getExternalPath()).isNull();
            assertThat(loss.getMessage()).contains("could not be represented");
        });
    }

    @Test
    void exportsAnEmptyModelWithAnEmptyMandatoryDiagram() throws Exception {
        UUID projectId = createProject();
        ModelResource source = objectMapper.treeToValue(
                getJson("/projects/{projectId}/model", projectId), ModelResource.class);
        assertThat(source.classifiers()).isEmpty();
        assertThat(source.relationships()).isEmpty();

        JsonNode response = objectMapper.readTree(export(projectId));
        XmiReadingResult reading = new XmiReader().read(Base64.getDecoder().decode(response.path("fileBase64").asText()));

        assertThat(reading.model().classifiers()).isEmpty();
        assertThat(reading.model().relationships()).isEmpty();
        assertThat(reading.diagrams()).hasSize(1);
        assertThat(reading.diagrams().get(0).figures()).isEmpty();
        assertThat(reading.diagrams().get(0).connectors()).isEmpty();
    }

    @Test
    void rejectsInvalidModelsAndOpenNameCollisions() throws Exception {
        UUID invalidProject = createProject();
        UUID invalidModel = modelId(invalidProject);
        jdbcTemplate.update("UPDATE diagrammer.model_version SET validation_state = 'INVALID' WHERE model_id = ?",
                invalidModel);
        assertExportRejected(invalidProject, "MODEL_INVALID");

        UUID collidingProject = createProject();
        transactionTemplate.executeWithoutResult(status -> {
            var model = modelRepository.findByProjectId(collidingProject).orElseThrow();
            classifierRepository.saveAllAndFlush(List.of(
                    Classifier.create(model, null, ClassifierKind.CLASS, "Duplicate", false),
                    Classifier.create(model, null, ClassifierKind.CLASS, "Duplicate", false)));
        });
        assertExportRejected(collidingProject, "NAME_COLLISION_BLOCKS");
    }

    private void assertReadBack(ModelResource expected, XmiReadingResult reading) {
        ModelResource actual = reading.model();
        assertThat(actual.id()).isEqualTo(expected.id());
        assertThat(actual.classifiers()).extracting(ClassifierResource::id)
                .containsExactlyInAnyOrderElementsOf(expected.classifiers().stream()
                        .map(ClassifierResource::id).toList());
        for (ClassifierResource classifier : expected.classifiers()) {
            ClassifierResource read = actual.classifiers().stream()
                    .filter(candidate -> candidate.id().equals(classifier.id())).findFirst().orElseThrow();
            assertThat(read.name()).isEqualTo(classifier.name());
            assertThat(read.kind()).isEqualTo(classifier.kind());
            assertThat(read.isAbstract()).isEqualTo(classifier.isAbstract());
            assertThat(read.namespaceId()).isEqualTo(classifier.namespaceId());
            assertThat(read.attributes()).extracting(attribute -> attribute.name() + ":" + attribute.type()
                    + ":" + attribute.multiplicity())
                    .containsExactlyElementsOf(classifier.attributes().stream()
                            .map(attribute -> attribute.name() + ":" + attribute.type() + ":"
                                    + attribute.multiplicity()).toList());
        }
        assertThat(actual.relationships()).extracting(RelationshipResource::id)
                .containsExactlyInAnyOrderElementsOf(expected.relationships().stream()
                        .map(RelationshipResource::id).toList());
        for (RelationshipResource relationship : expected.relationships()) {
            RelationshipResource read = actual.relationships().stream()
                    .filter(candidate -> candidate.id().equals(relationship.id())).findFirst().orElseThrow();
            assertThat(read.kind()).isEqualTo(relationship.kind());
            assertThat(read.name()).isEqualTo(relationship.name());
            assertThat(read.ends().stream().map(end -> end.classifierId() + ":" + end.ordinal() + ":" + end.role()
                    + ":" + end.multiplicity()).toList())
                    .containsExactlyInAnyOrderElementsOf(relationship.ends().stream()
                            .map(end -> end.classifierId() + ":" + end.ordinal() + ":" + end.role()
                                    + ":" + end.multiplicity()).toList());
        }
        ViewResource expectedView = expected.views().stream().filter(ViewResource::isDefault).findFirst().orElseThrow();
        ViewResource readView = actual.views().stream().filter(ViewResource::isDefault).findFirst().orElseThrow();
        assertThat(readView.id()).isEqualTo(expectedView.id());
        assertThat(readView.elementGeometry()).hasSameSizeAs(expectedView.elementGeometry());
        for (var geometry : expectedView.elementGeometry()) {
            var readGeometry = readView.elementGeometry().stream()
                    .filter(candidate -> candidate.targetId().equals(geometry.targetId())).findFirst().orElseThrow();
            assertThat(readGeometry.x()).isEqualTo(geometry.x());
            assertThat(readGeometry.y()).isEqualTo(geometry.y());
            assertThat(readGeometry.w()).isEqualTo(geometry.w());
            assertThat(readGeometry.h()).isEqualTo(geometry.h());
            assertThat(readGeometry.zOrder()).isEqualTo(geometry.zOrder());
        }
        assertThat(reading.diagrams()).hasSize(1);
        assertThat(reading.diagrams().get(0).figures()).hasSameSizeAs(expectedView.elementGeometry());
        assertThat(reading.diagrams().get(0).connectors()).hasSize(expected.relationships().size());
    }

    private void importFixture(UUID projectId, byte[] bytes) throws Exception {
        mockMvc.perform(authorized(post("/projects/{projectId}/xmi/import", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                        "fileBase64", Base64.getEncoder().encodeToString(bytes),
                        "checksum", sha256(bytes),
                        "mode", "REEMPLAZAR",
                        "idempotencyKey", "xmi-export-test-" + UUID.randomUUID())))))
                .andExpect(status().isOk());
    }

    private byte[] export(UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(authorized(post("/projects/{projectId}/xmi/export", projectId)
                .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsByteArray();
    }

    private void assertExportRejected(UUID projectId, String expectedCode) throws Exception {
        MvcResult result = mockMvc.perform(authorized(post("/projects/{projectId}/xmi/export", projectId)))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(error.path("code").asText()).isEqualTo(expectedCode);
    }

    private UUID createProject() throws Exception {
        MvcResult result = mockMvc.perform(authorized(post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "XMI export " + UUID.randomUUID())))))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText());
    }

    private JsonNode getJson(String path, Object... variables) throws Exception {
        MvcResult result = mockMvc.perform(authorized(get(path, variables).accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private UUID modelId(UUID projectId) throws Exception {
        return UUID.fromString(getJson("/projects/{projectId}/model", projectId).path("id").asText());
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
