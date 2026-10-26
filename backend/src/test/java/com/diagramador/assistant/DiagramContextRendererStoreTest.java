package com.diagramador.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.api.ModelResource;
import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.PersistenceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The model the assistant reads must say where each class is stored, or it will reason as if all were tables. */
class DiagramContextRendererStoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DiagramContextRenderer renderer = new DiagramContextRenderer(objectMapper);

    @Test
    void eachPersistedClassCarriesItsStoreAndOnlyAManualChoiceIsFlagged() throws Exception {
        ClassifierResource automatic = classifier("Order", ClassifierKind.CLASS)
                .withStore(PersistenceStore.SQL, "No document signal, so it stays relational.");
        ClassifierResource manual = new ClassifierResource(UUID.randomUUID(), "OrderLine", ClassifierKind.CLASS, false,
                null, List.of(), List.of(), List.of(), PersistenceStore.DOCUMENT, PersistenceStore.DOCUMENT,
                "Set manually.");

        JsonNode context = render(automatic, manual);

        JsonNode order = classifierNamed(context, "Order");
        assertThat(order.path("store").asText()).isEqualTo("SQL");
        assertThat(order.has("storeSetManually")).isFalse();
        JsonNode line = classifierNamed(context, "OrderLine");
        assertThat(line.path("store").asText()).isEqualTo("DOCUMENT");
        assertThat(line.path("storeSetManually").asBoolean()).isTrue();
    }

    @Test
    void aClassWithoutAStoreKeepsTheContextItHadBefore() throws Exception {
        JsonNode context = render(classifier("Sales", ClassifierKind.PACKAGE), classifier("Legacy", ClassifierKind.CLASS));

        assertThat(classifierNamed(context, "Sales").has("store")).isFalse();
        assertThat(classifierNamed(context, "Legacy").has("store")).isFalse();
        assertThat(classifierNamed(context, "Legacy").fieldNames()).toIterable()
                .containsExactly("name", "kind", "attributes", "operations");
    }

    @Test
    void bothPromptsExplainTheStoreAndThatCrossStoreReferencesAreIds() {
        for (String prompt : List.of(LlamaCppAssistantProvider.SYSTEM_PROMPT,
                LlamaCppAssistantProvider.CHAT_SYSTEM_PROMPT)) {
            assertThat(prompt).contains("\"store\"", "SQL", "DOCUMENT", "storeSetManually",
                    "referencia por id", "campo Storage");
        }
        assertThat(LlamaCppAssistantProvider.SYSTEM_PROMPT).contains("nunca lo propongas en un plan");
    }

    private JsonNode render(ClassifierResource... classifiers) throws Exception {
        ModelResource model = new ModelResource(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(),
                List.of(), List.of(classifiers), List.of());
        return objectMapper.readTree(renderer.renderDiagramOnly(model));
    }

    private static ClassifierResource classifier(String name, ClassifierKind kind) {
        return new ClassifierResource(UUID.randomUUID(), name, kind, false, null, List.of(), List.of(), List.of());
    }

    private static JsonNode classifierNamed(JsonNode context, String name) {
        for (JsonNode classifier : context.path("classifiers")) {
            if (name.equals(classifier.path("name").asText())) {
                return classifier;
            }
        }
        throw new AssertionError("No classifier named " + name);
    }
}
