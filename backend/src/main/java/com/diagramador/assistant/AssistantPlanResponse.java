package com.diagramador.assistant;

import java.util.List;
import java.util.Set;

public record AssistantPlanResponse(
        String intent,
        List<AssistantPlanOperation> operations,
        String question,
        String explanation,
        List<Diagnostic> diagnostics) {

    private static final Set<String> INTENTS = Set.of("PLAN", "EXPLAIN", "CLARIFY", "REJECT");

    public AssistantPlanResponse {
        if (!INTENTS.contains(intent)) {
            throw new IllegalArgumentException("Unsupported assistant plan intent: " + intent);
        }
        operations = operations == null ? List.of() : List.copyOf(operations);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public record Diagnostic(String code, String message) {
        private static final Set<String> CODES = Set.of(
                "UNKNOWN_OPERATION",
                "AMBIGUOUS_TARGET",
                "UNKNOWN_TARGET",
                "NAME_COLLISION",
                "REVIEW_REQUIRED",
                "UNPARSEABLE_DRAFT",
                "PROVIDER_DISABLED",
                // Apply-phase codes (IA4-03): assistant plan validation plus command pass-through.
                "MISSING_ARGUMENT",
                "INVALID_PAYLOAD",
                "PRECONDITION_FAILED",
                "REVISION_MISMATCH",
                "UNAUTHORIZED",
                "FORBIDDEN",
                "NAME_COLLISION_BLOCKS",
                "MODEL_INVALID");

        public Diagnostic {
            if (!CODES.contains(code)) {
                throw new IllegalArgumentException("Unsupported assistant diagnostic code: " + code);
            }
        }
    }
}
