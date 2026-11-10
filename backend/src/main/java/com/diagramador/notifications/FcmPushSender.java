package com.diagramador.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/** Firebase Cloud Messaging HTTP v1 sender. The OAuth access token comes from a separate supplier. */
public class FcmPushSender implements PushSender {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper json = new ObjectMapper();
    private final URI endpoint;
    private final Supplier<String> accessToken;

    public FcmPushSender(String baseUrl, String projectId, Supplier<String> accessToken) {
        this.endpoint = URI.create(baseUrl + "/v1/projects/" + projectId + "/messages:send");
        this.accessToken = accessToken;
    }

    @Override
    public Result send(String token, String title, String body, Map<String, String> data) {
        try {
            String bearer = accessToken.get();
            if (bearer == null || bearer.isBlank()) return Result.RETRY;
            var message = Map.of("message", Map.of("token", token, "data", data,
                    "notification", Map.of("title", title, "body", body)));
            var request = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + bearer)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(message))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) return Result.SENT;
            if (status == 404 || (status == 400 && unregistered(response.body()))) return Result.INVALID_TOKEN;
            return Result.RETRY;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Result.RETRY;
        } catch (Exception exception) {
            return Result.RETRY;
        }
    }

    private boolean unregistered(String body) {
        try {
            for (JsonNode detail : json.readTree(body).path("error").path("details")) {
                if ("UNREGISTERED".equals(detail.path("errorCode").asText())) return true;
            }
        } catch (Exception ignored) {
            // Unparseable errors are treated as transient.
        }
        return false;
    }
}
