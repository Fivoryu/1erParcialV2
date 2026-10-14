package com.diagramador.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class MobileGatewayResources {
    private MobileGatewayResources() { }
    public record BackendResource(UUID deploymentId, String name, String state, UUID runId, String contractHash) { }
    public record ContractResource(UUID deploymentId, UUID runId, String contractHash, JsonNode openApi) { }
    public record OperationRequest(@NotBlank @Size(min = 64, max = 64) String contractHash,
            @NotBlank @Pattern(regexp = "GET|POST|PUT|DELETE") String method,
            @NotBlank @Size(max = 512) String path, JsonNode body, @NotNull Boolean confirmed) { }
    public record OperationResource(int status, JsonNode body) { }
}
