package com.diagramador.assistant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One plan operation exactly as the plan endpoint returned it: type, targetId, args. */
public record AssistantApplyOp(String type, UUID targetId, Map<String, Object> args) {

    public AssistantApplyOp {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        args = args == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }
}
