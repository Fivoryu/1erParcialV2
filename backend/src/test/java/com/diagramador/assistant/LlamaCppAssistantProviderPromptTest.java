package com.diagramador.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Contract test for the declared prompt (IA4). The 2B model kept dropping required args
 * (rename's new name, attribute types, relationship kinds), so prompt v3 declares the args
 * schema explicitly. This is the SECOND and FINAL declared prompt iteration.
 */
class LlamaCppAssistantProviderPromptTest {

    private static final String PROMPT = LlamaCppAssistantProvider.SYSTEM_PROMPT;

    @Test
    void promptDeclaresTheV3ContractVersion() {
        assertThat(PROMPT).contains("ia4-01-prompt-v3");
        assertThat(PROMPT).doesNotContain("ia4-01-prompt-v2");
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
}
