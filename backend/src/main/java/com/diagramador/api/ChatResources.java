package com.diagramador.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ChatResources {

    private ChatResources() {
    }

    public record CreateMessageRequest(String body, List<UUID> attachmentIds) {
    }

    public record CreateSessionRequest(String title) {
    }

    /**
     * A conversation inside one diagram view. Sessions never copy each other's history (CA-01).
     */
    public record ChatSessionResource(UUID id, UUID viewId, String title, UUID createdByUserId,
            OffsetDateTime createdAt) {
    }

    public record MessageResource(UUID id, UUID sessionId, String kind, UUID authorUserId,
            String authorDisplayName, String body, OffsetDateTime createdAt, UUID replyTo,
            List<UUID> attachments) {
    }
}
