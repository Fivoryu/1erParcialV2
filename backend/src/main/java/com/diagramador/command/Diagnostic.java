package com.diagramador.command;

import java.util.UUID;

public record Diagnostic(ErrorCode code, String message, String scope, UUID id, String reason) {

    public Diagnostic(ErrorCode code, String message) {
        this(code, message, null, null, null);
    }
}
