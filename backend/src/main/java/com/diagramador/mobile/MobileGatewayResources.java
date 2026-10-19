package com.diagramador.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class MobileGatewayResources {
    private MobileGatewayResources() { }
    public record BackendResource(UUID deploymentId, String name, String state, UUID runId, String contractHash) { }
    public record ContractResource(UUID deploymentId, UUID runId, String contractHash, JsonNode openApi) { }
    /**
     * {@code query} is structured on purpose: the path never carries a raw query string, and the gateway encodes
     * only parameters the frozen contract declares for that GET operation.
     */
    public record OperationRequest(@NotBlank @Size(min = 64, max = 64) String contractHash,
            @NotBlank @Pattern(regexp = "GET|POST|PUT|DELETE") String method,
            @NotBlank @Size(max = 512) String path, JsonNode body, @NotNull Boolean confirmed,
            @Size(max = 24) List<@Valid QueryParameter> query) {
        public OperationRequest(String contractHash, String method, String path, JsonNode body, Boolean confirmed) {
            this(contractHash, method, path, body, confirmed, null);
        }
    }
    public record QueryParameter(@NotBlank @Size(max = 64) String name, @NotNull @Size(max = 256) String value) { }
    public record OperationResource(int status, JsonNode body) { }
}
