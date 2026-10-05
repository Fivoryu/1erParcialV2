package com.diagramador.assistant;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record AssistantApplyResponse(List<Step> steps, long finalRevision) {

    public AssistantApplyResponse {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public record Step(int index, String type, UUID targetId, String status, UUID operationId,
            Long resultRevision, List<AssistantPlanResponse.Diagnostic> diagnostics) {

        private static final Set<String> STATUSES = Set.of("ACCEPTED", "REJECTED", "SKIPPED");

        public Step {
            if (!STATUSES.contains(status)) {
                throw new IllegalArgumentException("Unsupported assistant apply step status: " + status);
            }
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }
}
