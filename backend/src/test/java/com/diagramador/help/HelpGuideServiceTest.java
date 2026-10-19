package com.diagramador.help;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramador.api.ApiException;
import com.diagramador.assistant.ChatAssistantProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class HelpGuideServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsPromptFromClientCorpusAndEditorStateParsesAnswerAndRestrictsSuggestions() throws Exception {
        AtomicReference<List<ChatAssistantProvider.Turn>> capturedTurns = new AtomicReference<>();
        ChatAssistantProvider provider = provider(capturedTurns,
                "{\"schemaVersion\":1,\"answer\":\"Open the generation section.\","
                        + "\"suggestedSlugs\":[\"generation\",\"not-in-corpus\",\"xmi\",\"generation\"]}");
        HelpGuideService service = new HelpGuideService(provider, objectMapper);
        HelpGuideRequest request = new HelpGuideRequest("How do I generate a backend?", List.of(
                new HelpGuideRequest.Topic("generation", "Generation", "Create and run a backend."),
                new HelpGuideRequest.Topic("xmi", "XMI interchange", "Import and export the model.")),
                new HelpGuideRequest.EditorState("generation", List.of("xmi", "generation")));

        HelpGuideResponse response = service.answer(request);

        assertThat(response.schemaVersion()).isEqualTo(1);
        assertThat(response.answer()).isEqualTo("Open the generation section.");
        assertThat(response.suggestedSlugs()).containsExactly("generation", "xmi");
        assertThat(capturedTurns.get()).extracting(ChatAssistantProvider.Turn::role)
                .contains("system", "user");
        String systemPrompt = capturedTurns.get().stream()
                .filter(turn -> "system".equals(turn.role()))
                .map(ChatAssistantProvider.Turn::content)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(systemPrompt.toLowerCase()).contains("únicamente", "temas", "breve");
        assertThat(systemPrompt.toLowerCase()).contains("fuera");

        String userContext = capturedTurns.get().stream()
                .filter(turn -> "user".equals(turn.role()))
                .map(ChatAssistantProvider.Turn::content)
                .findFirst()
                .orElseThrow();
        JsonNode promptData = objectMapper.readTree(userContext);
        assertThat(promptData.path("question").asText()).isEqualTo("How do I generate a backend?");
        assertThat(promptData.path("topics").get(0).path("slug").asText()).isEqualTo("generation");
        assertThat(promptData.path("topics").get(0).path("summary").asText())
                .isEqualTo("Create and run a backend.");
        assertThat(promptData.path("editorState").path("openSection").asText()).isEqualTo("generation");
        assertThat(promptData.path("editorState").path("sections").get(0).asText()).isEqualTo("xmi");
    }

    @Test
    void reportsDisabledProviderExplicitlyInsteadOfReturningAnInventedAnswer() {
        ChatAssistantProvider provider = new ChatAssistantProvider() {
            @Override
            public String chat(List<Turn> turns) {
                throw new IllegalStateException("AI assistant provider is disabled");
            }

            @Override
            public String systemPrompt() {
                return "unused";
            }
        };
        HelpGuideService service = new HelpGuideService(provider, objectMapper);
        HelpGuideRequest request = request();

        assertThatThrownBy(() -> service.answer(request))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.code()).isEqualTo("PROVIDER_DISABLED");
                    assertThat(exception.getMessage()).containsIgnoringCase("not enabled");
                });
    }

    @Test
    void rejectsUnparseableProviderOutputInsteadOfMakingUpAnAnswer() {
        AtomicReference<List<ChatAssistantProvider.Turn>> capturedTurns = new AtomicReference<>();
        HelpGuideService service = new HelpGuideService(provider(capturedTurns, "not a response"), objectMapper);

        assertThatThrownBy(() -> service.answer(request()))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(exception.code()).isEqualTo("GUIDE_RESPONSE_INVALID");
                });
    }

    private ChatAssistantProvider provider(AtomicReference<List<ChatAssistantProvider.Turn>> capturedTurns,
            String response) {
        return new ChatAssistantProvider() {
            @Override
            public String chat(List<Turn> turns) {
                capturedTurns.set(List.copyOf(turns));
                return response;
            }

            @Override
            public String systemPrompt() {
                return "unused";
            }
        };
    }

    private static HelpGuideRequest request() {
        return new HelpGuideRequest("Where is XMI?",
                List.of(new HelpGuideRequest.Topic("xmi", "XMI interchange", "Import or export.")),
                new HelpGuideRequest.EditorState(null, List.of("xmi", "generation")));
    }
}
