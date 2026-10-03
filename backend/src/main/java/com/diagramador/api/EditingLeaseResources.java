package com.diagramador.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

public final class EditingLeaseResources {

    private EditingLeaseResources() {
    }

    public record EditingLeaseView(UUID modelId, UUID editorUserId, String editorDisplayName, String targetType,
            UUID targetId, Instant acquiredAt, Instant expiresAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EditingLeaseEnvelope(EditingLeaseView lease) {
    }

    public record EditingLeaseRequest(String targetType, UUID targetId) {
    }
}
