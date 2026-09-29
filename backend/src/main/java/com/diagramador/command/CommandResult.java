package com.diagramador.command;

import java.util.List;
import java.util.UUID;

public record CommandResult(
        UUID operationId,
        CommandStatus status,
        Long resultRevision,
        List<Diagnostic> diagnostics,
        AuthorizationClassification authorizationClassification) {

    public CommandResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static CommandResult rejected(UUID operationId, ErrorCode code, String message) {
        return new CommandResult(operationId, CommandStatus.REJECTED, null,
                List.of(new Diagnostic(code, message)), null);
    }
}
