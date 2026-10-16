package com.diagramador.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runtime-backed token counter (T6/L45, S10): when the assistant runtime is reachable it counts
 * with llama-server's /tokenize — the authority per S10 —; otherwise it falls back to a
 * deterministic estimator (characters/4) documented as an approximation, so budgets and
 * thresholds keep working offline.
 */
@Component
public class RuntimeTokenCounter implements TokenCounter {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final boolean enabled;
    private final String url;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RuntimeTokenCounter(
            @Value("${diagrammer.assistant.enabled:false}") boolean enabled,
            @Value("${diagrammer.assistant.url:http://127.0.0.1:8099}") String url,
            ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.url = url;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (enabled) {
            Integer counted = countWithRuntime(text);
            if (counted != null) {
                return counted;
            }
        }
        return estimate(text);
    }

    private Integer countWithRuntime(String text) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(normalize(url) + "/tokenize"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(Map.of("content", text)), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            JsonNode tokens = objectMapper.readTree(response.body()).path("tokens");
            return tokens.isArray() ? tokens.size() : null;
        } catch (JsonProcessingException exception) {
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static String normalize(String base) {
        String trimmed = base == null ? "" : base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** Deterministic offline approximation; only used when the runtime cannot count. */
    static int estimate(String text) {
        return Math.max(1, text.length() / 4);
    }
}
