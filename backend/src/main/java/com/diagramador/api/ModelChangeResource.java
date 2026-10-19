package com.diagramador.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One accepted change of the model registry (T4/L41): the existing model_command row, exposed
 * for recovery and derived processing without any LLM involvement (CA-11, CA-19).
 */
public record ModelChangeResource(UUID operationId, String origin, UUID authorUserId, String authorDisplayName,
        long baseRevision, Long resultRevision, String operation, String targetType, UUID targetId,
        boolean knowledgePending, OffsetDateTime createdAt) {
}
