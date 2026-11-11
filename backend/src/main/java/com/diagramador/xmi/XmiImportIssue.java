package com.diagramador.xmi;

import com.diagramador.command.ErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Objects;

/**
 * One finding of the import checks. {@code code} is stable, {@code message} is user-facing Spanish and
 * {@code options} is the closed set of choices a user (or the assistant) may pick to resolve a blocker.
 * The rejection fields keep the exact code and text the command handler rejects with, so the handler and the
 * dry run share one source of truth without changing the existing import error response.
 */
public record XmiImportIssue(
        String code,
        Severity severity,
        String subject,
        String name,
        String message,
        List<Option> options,
        @JsonIgnore ErrorCode rejectionCode,
        @JsonIgnore String rejectionMessage) {

    public enum Severity {
        BLOCKER,
        WARNING
    }

    /** The closed answers a blocker accepts; each maps to an {@link XmiResolution.Action}. */
    public enum Option {
        RENAME,
        SKIP,
        KEEP_VIEW
    }

    public XmiImportIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        options = options == null ? List.of() : List.copyOf(options);
    }

    static XmiImportIssue blocker(String code, String subject, String name, String message, List<Option> options,
            ErrorCode rejectionCode, String rejectionMessage) {
        return new XmiImportIssue(code, Severity.BLOCKER, subject, name, message, options, rejectionCode,
                rejectionMessage);
    }

    public static XmiImportIssue warning(String code, String subject, String name, String message) {
        return new XmiImportIssue(code, Severity.WARNING, subject, name, message, List.of(), null, null);
    }

    /** A blocker produced by the semantic model rules rather than by the exchange checks. */
    public static XmiImportIssue modelBlocker(String code, String subject, String name, String message) {
        return new XmiImportIssue(code, Severity.BLOCKER, subject, name, message, List.of(),
                ErrorCode.PRECONDITION_FAILED, message);
    }
}
