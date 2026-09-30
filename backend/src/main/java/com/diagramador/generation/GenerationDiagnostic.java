package com.diagramador.generation;

import com.diagramador.domain.SeverityKind;
import java.util.Objects;
import java.util.UUID;

/** A stable diagnostic produced while mapping a frozen generation snapshot. */
public record GenerationDiagnostic(
        String code,
        SeverityKind severity,
        UUID elementId,
        String feature,
        String message,
        boolean blocking) {

    public GenerationDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(message, "message");
    }

    /*
     * The command-layer Diagnostic is intentionally not reused: it carries ErrorCode plus command scope/id/reason
     * fields, which do not match the generation contract's stable code/severity/elementId/feature/blocking shape.
     */
}
