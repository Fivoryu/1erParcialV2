package com.diagramador.command;

import java.util.List;
import java.util.UUID;

/** Canonical command envelope shared by every command adapter. */
public record OperationEnvelope(
        UUID operationId,
        String type,
        Author author,
        UUID projectId,
        UUID modelId,
        Long baseRevision,
        List<Target> targets,
        List<Precondition> preconditions,
        Object payload,
        CommandOrigin origin,
        UUID causalParentOperationId,
        String idempotencyKey) {

    public OperationEnvelope {
        targets = targets == null ? List.of() : List.copyOf(targets);
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
    }
}
