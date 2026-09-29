package com.diagramador.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<Map<String, Object>> details;
    private final UUID operationId;

    public ApiException(HttpStatus status, String code, String message, List<Map<String, Object>> details,
            UUID operationId) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
        this.operationId = operationId;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<Map<String, Object>> details() {
        return details;
    }

    public UUID operationId() {
        return operationId;
    }
}
