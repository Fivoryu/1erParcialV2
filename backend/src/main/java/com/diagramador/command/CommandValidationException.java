package com.diagramador.command;

/** Runtime validation failure that the command service exposes as a stable diagnostic. */
public final class CommandValidationException extends RuntimeException {

    private final ErrorCode code;

    public CommandValidationException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
