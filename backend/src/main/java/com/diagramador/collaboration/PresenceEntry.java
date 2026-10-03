package com.diagramador.collaboration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable state for one live collaboration session. */
public record PresenceEntry(UUID sessionId, UUID userId, String displayName, String color,
        UUID targetElementId, Double cursorX, Double cursorY, Instant joinedAt, Instant lastSeenAt) {

    public PresenceEntry {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(joinedAt, "joinedAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    }
}
