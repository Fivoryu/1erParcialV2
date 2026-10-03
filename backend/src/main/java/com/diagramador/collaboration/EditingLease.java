package com.diagramador.collaboration;

import com.diagramador.domain.TargetType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record of the editor currently associated with one UML model and the target they are editing.
 * A target may be absent before the editor selects an element; when present, its type and ID are required
 * together. The lease is ephemeral and exists only in the memory of a single application instance.
 */
public record EditingLease(UUID modelId, UUID editorUserId, String editorDisplayName, TargetType targetType,
        UUID targetId, Instant acquiredAt, Instant expiresAt) {

    public EditingLease {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(editorUserId, "editorUserId");
        Objects.requireNonNull(editorDisplayName, "editorDisplayName");
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if ((targetType == null) != (targetId == null)) {
            throw new IllegalArgumentException("targetType and targetId must be provided together.");
        }
    }

    /** Returns whether this lease is still live at the supplied instant (the expiry instant is not live). */
    public boolean isLive(Instant now) {
        return now.isBefore(expiresAt);
    }
}
