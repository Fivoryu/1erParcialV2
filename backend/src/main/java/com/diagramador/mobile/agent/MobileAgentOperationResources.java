package com.diagramador.mobile.agent;

import com.diagramador.mobile.MobileGatewayResources.QueryParameter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

public final class MobileAgentOperationResources {
    private MobileAgentOperationResources() { }
    public record Request(@NotNull UUID operationId, @NotBlank @Size(min = 64, max = 64)
            @Pattern(regexp = "[a-f0-9]{64}") String contractHash,
            @NotBlank @Pattern(regexp = "GET|POST|PUT|DELETE") String method,
            @NotBlank @Size(max = 512) String path, JsonNode body, @Size(max = 64) String confirmationToken,
            @Size(max = 24) List<@Valid QueryParameter> query) {
        public Request(UUID operationId, String contractHash, String method, String path, JsonNode body,
                String confirmationToken) {
            this(operationId, contractHash, method, path, body, confirmationToken, null);
        }
    }
    public record Receipt(UUID operationId, String contractHash, String method, String path, JsonNode body,
            String state, String confirmationToken, Integer status, JsonNode result, String error) { }

    public static String canonical(ObjectMapper json, Request request) {
        ObjectNode value = json.createObjectNode();
        value.put("contractHash", request.contractHash());
        value.put("method", request.method());
        value.put("path", request.path());
        value.set("body", sorted(json, request.body() == null ? json.nullNode() : request.body()));
        // Added only when present, so intents persisted before structured queries keep their canonical form.
        if (request.query() != null && !request.query().isEmpty()) {
            var query = value.putArray("query");
            request.query().forEach(parameter -> query.addObject()
                    .put("name", parameter.name()).put("value", parameter.value()));
        }
        return value.toString();
    }

    private static JsonNode sorted(ObjectMapper json, JsonNode value) {
        if (value.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            ObjectNode object = json.createObjectNode();
            fields.forEach((key, child) -> object.set(key, sorted(json, child)));
            return object;
        }
        if (value.isArray()) {
            var array = json.createArrayNode();
            value.forEach(child -> array.add(sorted(json, child)));
            return array;
        }
        return value;
    }

    public static String token(UUID user, UUID project, UUID deployment, UUID operation, String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (user + ":" + project + ":" + deployment + ":" + operation + ":" + canonical).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
