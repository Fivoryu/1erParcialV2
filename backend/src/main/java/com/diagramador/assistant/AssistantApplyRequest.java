package com.diagramador.assistant;

import java.util.List;

public record AssistantApplyRequest(List<AssistantApplyOp> operations, long baseRevision) {

    public AssistantApplyRequest {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
