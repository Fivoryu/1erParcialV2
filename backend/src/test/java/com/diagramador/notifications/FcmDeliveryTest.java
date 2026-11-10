package com.diagramador.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FcmDeliveryTest {
    @TempDir Path dir;

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void exchangesServiceAccountKeyCachesTheTokenAndSendsWithIt() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        Path key = dir.resolve("key.json");
        Files.writeString(key, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                Map.of("client_email", "svc@example.iam", "private_key", pem, "token_uri", "unused")));

        var tokenCalls = new AtomicInteger();
        var tokenBody = new CopyOnWriteArrayList<String>();
        var fcmAuth = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            tokenCalls.incrementAndGet();
            tokenBody.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, 200, "{\"access_token\":\"ya29.test\",\"expires_in\":3600}");
        });
        server.createContext("/v1/projects/sw1/messages:send", exchange -> {
            fcmAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String token = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (token.contains("dead")) reply(exchange, 404, "{}");
            else reply(exchange, 200, "{\"name\":\"ok\"}");
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var sender = new FcmPushSender(base, "sw1",
                    new ServiceAccountTokenProvider(() -> {
                        try { return Files.readString(key); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                    }, base + "/token"));
            assertThat(sender.send("live", "t", "b", Map.of("k", "v"))).isEqualTo(PushSender.Result.SENT);
            assertThat(sender.send("dead", "t", "b", Map.of("k", "v"))).isEqualTo(PushSender.Result.INVALID_TOKEN);
            assertThat(tokenCalls.get()).isEqualTo(1);
            assertThat(fcmAuth).containsOnly("Bearer ya29.test");
            assertThat(tokenBody.get(0)).startsWith("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unreadableKeyAsksForRetryInsteadOfFailing() {
        var provider = new ServiceAccountTokenProvider(dir.resolve("missing.json"), null);
        var sender = new FcmPushSender("http://127.0.0.1:1", "sw1", provider);
        assertThat(sender.send("t", "t", "b", Map.of())).isEqualTo(PushSender.Result.RETRY);
    }
}
