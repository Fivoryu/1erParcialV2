package com.diagramador.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Exchanges a Google service account key for short-lived OAuth access tokens. The key file is read from a
 * configured path only; its content is never logged and never leaves this class except as a signed assertion.
 */
public class ServiceAccountTokenProvider implements Supplier<String> {
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Supplier<String> keyJson;
    private final String tokenUriOverride;
    private String cached;
    private Instant expiresAt = Instant.EPOCH;

    public ServiceAccountTokenProvider(Path file, String tokenUriOverride) {
        this((Supplier<String>) () -> {
            try {
                return Files.readString(file);
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }, tokenUriOverride);
    }

    /** The key content comes from an environment variable or secret store instead of a file. */
    public ServiceAccountTokenProvider(Supplier<String> keyJson, String tokenUriOverride) {
        this.keyJson = keyJson;
        this.tokenUriOverride = tokenUriOverride;
    }

    /** Returns a valid token, or null when it cannot be obtained so the caller retries later. */
    @Override
    public synchronized String get() {
        if (cached != null && Instant.now().isBefore(expiresAt.minusSeconds(60))) return cached;
        try {
            JsonNode key = json.readTree(keyJson.get());
            String tokenUri = tokenUriOverride == null || tokenUriOverride.isBlank()
                    ? key.path("token_uri").asText() : tokenUriOverride;
            String assertion = assertion(key, tokenUri);
            var request = HttpRequest.newBuilder(URI.create(tokenUri)).timeout(TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=" + URLEncoder.encode(
                            "urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                            + "&assertion=" + assertion)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            JsonNode body = json.readTree(response.body());
            cached = body.path("access_token").asText(null);
            expiresAt = Instant.now().plusSeconds(body.path("expires_in").asLong(300));
            return cached;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    private String assertion(JsonNode key, String audience) throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = b64(json.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String claims = b64(json.writeValueAsBytes(Map.of("iss", key.path("client_email").asText(),
                "scope", SCOPE, "aud", audience, "iat", now, "exp", now + 3600)));
        String pem = key.path("private_key").asText()
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        var privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        String unsigned = header + "." + claims;
        signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
        return unsigned + "." + b64(signature.sign());
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
