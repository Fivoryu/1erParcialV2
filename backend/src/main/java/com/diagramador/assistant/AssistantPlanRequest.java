package com.diagramador.assistant;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record AssistantPlanRequest(@NotBlank String utterance, long baseRevision, UUID selection) {

    public AssistantPlanRequest {
        if (utterance == null || utterance.isBlank()) {
            throw new IllegalArgumentException("utterance must not be blank");
        }
    }
}
