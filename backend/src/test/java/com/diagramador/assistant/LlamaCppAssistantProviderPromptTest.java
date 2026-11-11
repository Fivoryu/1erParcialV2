package com.diagramador.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the declared prompt (IA4). The 2B model kept dropping required args
 * (rename's new name, attribute types, relationship kinds), so prompt v3 declares the args
 * schema explicitly. This is the SECOND and FINAL declared prompt iteration.
 */
class LlamaCppAssistantProviderPromptTest {

    private static final String PROMPT = LlamaCppAssistantProvider.SYSTEM_PROMPT;

    @Test
    void textOnlyDraftMessagesStayExactlyAsBeforeTheImageRoute() {
        List<java.util.Map<String, Object>> messages = LlamaCppAssistantProvider.draftMessages("{\"ctx\":1}",
                "Creá Paciente", null, null);

        assertThat(messages).containsExactly(
                java.util.Map.of("role", "system", "content", LlamaCppAssistantProvider.SYSTEM_PROMPT),
                java.util.Map.of("role", "user", "content", "{\"ctx\":1}\n\nPedido: Creá Paciente"));
    }

    @Test
    void anImageDraftKeepsTheSystemPromptAndAppendsTheInstructionToTheMultimodalUserTurn() {
        List<java.util.Map<String, Object>> messages = LlamaCppAssistantProvider.draftMessages("{\"ctx\":1}",
                "Creá Paciente", "QUJD", "image/jpeg");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isEqualTo(
                java.util.Map.of("role", "system", "content", LlamaCppAssistantProvider.SYSTEM_PROMPT));
        List<?> parts = (List<?>) messages.get(1).get("content");
        java.util.Map<?, ?> text = (java.util.Map<?, ?>) parts.get(0);
        assertThat((String) text.get("text")).startsWith("{\"ctx\":1}\n\nPedido: Creá Paciente")
                .contains("foto adjunta es un diagrama de clases: transcribilo");
        java.util.Map<?, ?> image = (java.util.Map<?, ?>) parts.get(1);
        assertThat(image.get("type")).isEqualTo("image_url");
        assertThat(((java.util.Map<?, ?>) image.get("image_url")).get("url"))
                .isEqualTo("data:image/jpeg;base64,QUJD");
    }

    @Test
    void promptDeclaresTheV3ContractVersion() {
        assertThat(PROMPT).contains("ia4-02-prompt-v7");
        assertThat(PROMPT).doesNotContain("ia4-01-prompt-v4").doesNotContain("ia4-01-prompt-v3");
    }

    @Test
    void promptTeachesMovingAClassAndTheGeneratorDateType() {
        assertThat(PROMPT).contains("classifier.move");
        assertThat(PROMPT).contains("\"x\"", "\"y\"", "\"dx\"", "\"dy\"");
        // The generator only maps LocalDate: the prompt must not teach the bare "Date" type again.
        assertThat(PROMPT).contains("\"LocalDate\"").doesNotContain("\"Date\"");
    }

    @Test
    void promptTeachesTheAssociationClassOfAnAssociation() {
        assertThat(PROMPT).contains("\"associationClass\": {\"classifierName\"");
        assertThat(PROMPT).contains("\"associationClass\": null suelta");
    }

    @Test
    void promptDeclaresTheArgsSchemaForEachCatalogOperation() {
        // attribute.add / attribute.update MUST carry {name, type}
        assertThat(PROMPT).contains("attribute.add");
        assertThat(PROMPT).contains("attribute.update");
        assertThat(PROMPT).contains("\"type\"");
        assertThat(PROMPT).contains("String");
        // classifier.rename MUST carry {newName}
        assertThat(PROMPT).contains("classifier.rename");
        assertThat(PROMPT).contains("\"newName\"");
        // classifier.create MUST carry {name}
        assertThat(PROMPT).contains("classifier.create");
        assertThat(PROMPT).contains("\"name\"");
        // relationship.create MUST carry {kind} with the exact kind vocabulary
        assertThat(PROMPT).contains("relationship.create");
        assertThat(PROMPT).contains("\"kind\"");
        assertThat(PROMPT).contains("ASSOCIATION");
        assertThat(PROMPT).contains("COMPOSITION");
        assertThat(PROMPT).contains("AGGREGATION");
        // constraint.add is not in the catalog: the prompt must say so explicitly
        assertThat(PROMPT).contains("constraint.add");
    }

    @Test
    void promptTeachesDependenciesAndTheirStereotypesWithTheExactVocabulary() {
        assertThat(PROMPT).contains("DEPENDENCY", "\"stereotype\"");
        for (String stereotype : List.of("USE", "IMPORT", "ACCESS", "INCLUDE", "EXTEND", "INSTANTIATE", "CALL",
                "CREATE", "DERIVE", "REFINE", "TRACE")) {
            assertThat(PROMPT).as("stereotype %s", stereotype).contains("\"" + stereotype + "\"");
        }
        // Only a dependency may carry one, and an update may set it on its own.
        assertThat(PROMPT).contains("solo con DEPENDENCY");
        assertThat(PROMPT).contains("relationship.update \u2192 args");
    }

    @Test
    void theChatPromptAddressesTheAskerByNameAndAllowsGeneralTheory() {
        String prompt = LlamaCppAssistantProvider.CHAT_SYSTEM_PROMPT;
        // Who wrote each message travels as "[Name]: ..." and the model must answer to that person by name.
        assertThat(prompt).contains("[Nombre]:", "dirigite por su nombre", "Atendiendo a tu consulta");
        // Conceptual course questions are answered from general knowledge, not refused for lack of context.
        assertThat(prompt).contains("conocimiento general", "entidad débil", "atributo derivado");
        // Facts about the diagram itself still come only from the provided context.
        assertThat(prompt).contains("solo en el contexto provisto");
    }

    @Test
    void theChatPromptKnowsRelationshipsMayCarryAStereotype() {
        assertThat(LlamaCppAssistantProvider.CHAT_SYSTEM_PROMPT).contains("\"stereotype\"", "DEPENDENCY");
    }
}
