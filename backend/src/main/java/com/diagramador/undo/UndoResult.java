package com.diagramador.undo;

import com.diagramador.command.CommandResult;
import java.util.Set;
import java.util.UUID;

/** Outcome of one undo/redo request: the executor result or a declared, code-carrying rejection. */
public record UndoResult(String status, String code, String message, UUID operationId, Long resultRevision) {

    private static final Set<String> STATUSES = Set.of("ACCEPTED", "REJECTED");

    public UndoResult {
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("Unsupported undo result status: " + status);
        }
    }

    public static UndoResult accepted(CommandResult result) {
        return new UndoResult("ACCEPTED", null, null, result.operationId(), result.resultRevision());
    }

    public static UndoResult rejected(String code, String message) {
        return new UndoResult("REJECTED", code, message, null, null);
    }

    /** The inverse command itself was rejected by the normal executor; surface its diagnostic. */
    public static UndoResult ofRejection(CommandResult result) {
        String code = result.diagnostics().isEmpty() ? "MODEL_INVALID" : result.diagnostics().get(0).code().name();
        String message = result.diagnostics().isEmpty() ? "The inverse command was rejected."
                : result.diagnostics().get(0).message();
        return new UndoResult("REJECTED", code, message, result.operationId(), null);
    }
}
