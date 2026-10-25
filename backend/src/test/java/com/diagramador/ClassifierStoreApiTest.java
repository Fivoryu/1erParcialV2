package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The model the editor and the assistant read carries the store of every class and why it was chosen. */
@SpringBootTest
@AutoConfigureMockMvc
class ClassifierStoreApiTest extends AuthenticatedApiTest {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    @Autowired
    ClassifierStoreApiTest(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    @Test
    void theModelReportsTheAutomaticStoreTheManualOverrideAndTheReturnToAutomatic() throws Exception {
        JsonNode project = objectMapper.readTree(mockMvc.perform(authorized(post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "Store api " + UUID.randomUUID())))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
        UUID projectId = UUID.fromString(project.path("id").asText());
        JsonNode model = model(projectId);
        UUID modelId = UUID.fromString(model.path("id").asText());
        UUID orderId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        long revision = command(projectId, modelId, model.path("revision").asLong(), "classifier.create", List.of(),
                Map.of("id", orderId, "name", "Order", "kind", "CLASS")).path("resultRevision").asLong();
        revision = command(projectId, modelId, revision, "classifier.create", List.of(),
                Map.of("id", lineId, "name", "OrderLine", "kind", "CLASS")).path("resultRevision").asLong();
        revision = command(projectId, modelId, revision, "classifier.create", List.of(),
                Map.of("id", packageId, "name", "Sales", "kind", "PACKAGE")).path("resultRevision").asLong();
        Map<String, Object> composition = new HashMap<>();
        composition.put("id", UUID.randomUUID());
        composition.put("kind", "COMPOSITION");
        composition.put("source", Map.of("classifierId", orderId));
        composition.put("target", Map.of("classifierId", lineId));
        revision = command(projectId, modelId, revision, "relationship.create", List.of(
                Map.of("scope", "classifier", "id", orderId), Map.of("scope", "classifier", "id", lineId)),
                composition).path("resultRevision").asLong();

        JsonNode automatic = model(projectId);
        assertThat(classifier(automatic, "Order").path("store").asText()).isEqualTo("SQL");
        assertThat(classifier(automatic, "Order").path("storeReason").asText())
                .isEqualTo("No document signal, so it stays relational.");
        assertThat(classifier(automatic, "OrderLine").path("store").asText()).isEqualTo("DOCUMENT");
        assertThat(classifier(automatic, "OrderLine").path("storeReason").asText())
                .isEqualTo("Part of a composition owned by Order.");
        assertThat(classifier(automatic, "OrderLine").has("storeOverride")).isFalse();
        assertThat(classifier(automatic, "Sales").has("store")).as("a package is not persisted").isFalse();

        revision = command(projectId, modelId, revision, "classifier.setStore",
                List.of(Map.of("scope", "classifier", "id", lineId)), Map.of("store", "SQL")).path("resultRevision")
                .asLong();
        JsonNode manual = model(projectId);
        assertThat(classifier(manual, "OrderLine").path("storeOverride").asText()).isEqualTo("SQL");
        assertThat(classifier(manual, "OrderLine").path("store").asText()).isEqualTo("SQL");
        assertThat(classifier(manual, "OrderLine").path("storeReason").asText()).isEqualTo("Set manually.");

        command(projectId, modelId, revision, "classifier.setStore",
                List.of(Map.of("scope", "classifier", "id", lineId)), Collections.singletonMap("store", null));
        JsonNode backToAutomatic = model(projectId);
        assertThat(classifier(backToAutomatic, "OrderLine").has("storeOverride")).isFalse();
        assertThat(classifier(backToAutomatic, "OrderLine").path("store").asText()).isEqualTo("DOCUMENT");
    }

    private JsonNode classifier(JsonNode model, String name) {
        for (JsonNode classifier : model.path("classifiers")) {
            if (name.equals(classifier.path("name").asText())) {
                return classifier;
            }
        }
        throw new AssertionError("No classifier named " + name);
    }

    private JsonNode model(UUID projectId) throws Exception {
        return objectMapper.readTree(mockMvc.perform(authorized(get("/projects/{projectId}/model", projectId)
                .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private JsonNode command(UUID projectId, UUID modelId, long baseRevision, String type,
            List<Map<String, Object>> targets, Map<String, Object> payload) throws Exception {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("operationId", UUID.randomUUID());
        envelope.put("type", type);
        envelope.put("projectId", projectId);
        envelope.put("modelId", modelId);
        envelope.put("baseRevision", baseRevision);
        envelope.put("targets", targets);
        envelope.put("preconditions", List.of());
        envelope.put("payload", payload);
        envelope.put("origin", "MANUAL");
        envelope.put("idempotencyKey", UUID.randomUUID().toString());
        JsonNode result = objectMapper.readTree(mockMvc.perform(authorized(post("/projects/{projectId}/commands",
                projectId).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(envelope))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(result.path("status").asText()).as(result.toString()).isEqualTo("ACCEPTED");
        return result;
    }
}
