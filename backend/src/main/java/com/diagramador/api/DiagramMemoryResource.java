package com.diagramador.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One diagram memory as exposed to clients (T5/L43): kind and lifecycle status plus the evidence
 * linkage, so any consumer can audit where the memory came from (CA-15).
 */
public record DiagramMemoryResource(UUID memoryId, String kind, String status, String content,
        UUID sourceSessionId, UUID sourceMessageId, UUID replacedBy, OffsetDateTime createdAt) {
}
