package com.diagramador.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L5 of the IA follow-up: Docker Model Runner rejects a completions request without the
 * {@code model} field ("model is required", HTTP 400), which surfaced to users as a bare
 * 500 on every assistant ask. The provider must send the configured model when one is set
 * and omit the field otherwise, preserving the bare llama.cpp behaviour.
 */
class LlamaCppAssistantProviderModelFieldTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<JsonNode> receivedBody = new AtomicReference<>();

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            receivedBody.set(MAPPER.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] answer = """
                    {"choices":[{"message":{"role":"assistant","content":"{\\"intent\\":\\"explain\\",\\"operations\\":[],\\"question\\":null,\\"explanation\\":\\"ok\\"}"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private LlamaCppAssistantProvider providerWithModel(String model) {
        return new LlamaCppAssistantProvider(true,
                "http://127.0.0.1:" + server.getAddress().getPort(), model, MAPPER);
    }

    @Test
    void sendsTheConfiguredModelWhenOneIsSet() {
        LlamaCppAssistantProvider provider = providerWithModel("huggingface.co/ggml-org/qwen3.5-0.8b-gguf:Q4_0");
        provider.chat(List.of(new ChatAssistantProvider.Turn("user", "hola")));
        assertThat(receivedBody.get().has("model")).isTrue();
        assertThat(receivedBody.get().path("model").asText())
                .isEqualTo("huggingface.co/ggml-org/qwen3.5-0.8b-gguf:Q4_0");
    }

    @Test
    void omitsTheModelFieldWhenNoModelIsConfigured() {
        LlamaCppAssistantProvider provider = providerWithModel("");
        provider.chat(List.of(new ChatAssistantProvider.Turn("user", "hola")));
        assertThat(receivedBody.get().has("model")).isFalse();
    }

}
