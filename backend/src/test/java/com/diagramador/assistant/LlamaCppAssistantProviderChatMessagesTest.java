package com.diagramador.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Chat templates such as Qwen's reject a system message that is not the first message ("System message must be
 * at the beginning"). The provider owns the leading system prompt, so system turns supplied by callers must be
 * folded into that single leading message instead of being sent as later system messages.
 */
class LlamaCppAssistantProviderChatMessagesTest {

    private static final String COMPLETION = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private HttpServer server;
    private LlamaCppAssistantProvider provider;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = COMPLETION.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        provider = new LlamaCppAssistantProvider(true,
                "http://127.0.0.1:" + server.getAddress().getPort(), objectMapper);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void callerSystemTurnsAreFoldedIntoTheSingleLeadingSystemMessage() throws IOException {
        String answer = provider.chat(List.of(
                new ChatAssistantProvider.Turn("system", "Memory agent instructions."),
                new ChatAssistantProvider.Turn("user", "Context turn."),
                new ChatAssistantProvider.Turn("assistant", "{\"memoryAction\":{}}"),
                new ChatAssistantProvider.Turn("user", "Action result.")));

        assertThat(answer).isEqualTo("ok");
        JsonNode messages = objectMapper.readTree(requestBody.get()).path("messages");
        assertThat(messages).extracting(message -> message.path("role").asText())
                .containsExactly("system", "user", "assistant", "user");
        assertThat(messages.get(0).path("content").asText())
                .startsWith(LlamaCppAssistantProvider.CHAT_SYSTEM_PROMPT)
                .endsWith("Memory agent instructions.");
        assertThat(messages.get(1).path("content").asText()).isEqualTo("Context turn.");
        assertThat(messages.get(3).path("content").asText()).isEqualTo("Action result.");
    }

    @Test
    void conversationWithoutSystemTurnsKeepsOnlyTheProviderSystemPrompt() throws IOException {
        provider.chat(List.of(new ChatAssistantProvider.Turn("user", "Question.")));

        JsonNode messages = objectMapper.readTree(requestBody.get()).path("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).path("content").asText()).isEqualTo(LlamaCppAssistantProvider.CHAT_SYSTEM_PROMPT);
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).path("content").asText()).isEqualTo("Question.");
    }
}
