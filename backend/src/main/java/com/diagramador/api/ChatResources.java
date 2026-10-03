package com.diagramador.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class ChatResources {

    private ChatResources() {
    }

    public record CreateMessageRequest(String body) {
    }

    public record MessageResource(UUID id, UUID authorUserId, String authorDisplayName, String body,
            OffsetDateTime createdAt) {
    }
}
