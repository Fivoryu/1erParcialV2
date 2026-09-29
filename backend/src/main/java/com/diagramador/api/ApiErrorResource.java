package com.diagramador.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiErrorResource(String code, String message, List<Map<String, Object>> details, UUID operationId) {

    public ApiErrorResource {
        details = details == null ? List.of() : List.copyOf(details);
    }
}
