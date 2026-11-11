package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.api.ModelResource;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.TargetType;
import com.diagramador.xmi.XmiReader;
import com.diagramador.xmi.XmiSemanticEmitter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * S8: an XMI import must end clean on every route into the handler. The generic /commands endpoint must not be a
 * side door for a model with semantic ERRORs that the dedicated import route refuses.
 */
@SpringBootTest
@AutoConfigureMockMvc
class XmiCommandsCleanGateTest extends AuthenticatedApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void aReadingWithSemanticErrorsThroughTheCommandsEndpointIsRejectedAndNothingIsWritten() throws Exception {
        UUID projectId = createProject();
        JsonNode before = getJson("/projects/{projectId}/model", projectId);

        JsonNode result = executeImport(projectId, emit(modelWithDuplicateAttributeNames()));

        assertThat(result.path("status").asText()).as("result: %s", result).isEqualTo("REJECTED");
        assertThat(result.at("/diagnostics/0/message").asText())
                .contains("La importación no quedaría limpia", "DUPLICATE_MEMBER_NAME");
        assertThat(getJson("/projects/{projectId}/model", projectId)).isEqualTo(before);
    }

    @Test
    void aCleanReadingThroughTheCommandsEndpointIsStillAccepted() throws Exception {
        UUID projectId = createProject();

        JsonNode result = executeImport(projectId, emit(cleanModel()));

        assertThat(result.path("status").asText()).as("result: %s", result).isEqualTo("ACCEPTED");
        assertThat(getJson("/projects/{projectId}/model", projectId).path("classifiers")).hasSize(1);
    }

    private JsonNode executeImport(UUID projectId, byte[] bytes) throws Exception {
        JsonNode model = getJson("/projects/{projectId}/model", projectId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("mode", "REEMPLAZAR");
        payload.put("profile", "Enterprise Architect 15.0.1514 / XMI 2.1");
        payload.put("checksum", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        payload.put("readingResult", objectMapper.valueToTree(new XmiReader().read(bytes)));
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("operationId", UUID.randomUUID());
        envelope.put("type", "xmi.import");
        envelope.put("projectId", projectId);
        envelope.put("modelId", UUID.fromString(model.path("id").asText()));
        envelope.put("baseRevision", model.path("revision").asLong());
        envelope.put("targets", List.of());
        envelope.put("preconditions", List.of());
        envelope.put("payload", payload);
        envelope.put("origin", "IMPORT");
        envelope.put("idempotencyKey", UUID.randomUUID().toString());
        MvcResult response = mockMvc.perform(authorized(post("/projects/{projectId}/commands", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(envelope))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(response.getResponse().getContentAsByteArray());
    }

    private UUID createProject() throws Exception {
        MvcResult result = mockMvc.perform(authorized(post("/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("name", "CleanGate " + UUID.randomUUID())))))
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

    private static UUID uid(long value) {
        return UUID.fromString("00000000-0000-0000-0000-%012x".formatted(value));
    }

    private static ModelResource.AttributeResource stringAttribute(long id, String name) {
        return new ModelResource.AttributeResource(uid(id), name, "ATTRIBUTE", "PUBLIC",
                MultiplicityKind.EXACTLY_ONE, null, uid(9000), "String", 0);
    }

    private static ModelResource modelOf(List<ModelResource.AttributeResource> attributes) {
        var classifier = new ModelResource.ClassifierResource(uid(900), "Owner", ClassifierKind.CLASS, false, null,
                attributes, List.of(), List.of());
        var view = new ModelResource.ViewResource(uid(300), "Importado", true,
                List.of(new ModelResource.GeometryResource(uid(500), TargetType.CLASSIFIER, classifier.id(), 40, 60,
                        200, 100, 0)), List.of());
        return new ModelResource(uid(1), uid(2), 3, uid(3), List.of(view), List.of(classifier), List.of());
    }

    private static ModelResource modelWithDuplicateAttributeNames() {
        return modelOf(List.of(stringAttribute(10, "name"), stringAttribute(11, "name")));
    }

    private static ModelResource cleanModel() {
        return modelOf(List.of(stringAttribute(10, "name")));
    }

    private static byte[] emit(ModelResource model) {
        return new XmiSemanticEmitter().emit(model).document().getBytes(StandardCharsets.UTF_8);
    }
}
