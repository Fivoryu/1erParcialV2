package com.diagramador.validation;

import com.diagramador.domain.SeverityKind;
import java.util.Objects;
import java.util.UUID;

/** A stable diagnostic produced while validating the canonical UML model. */
public record ModelValidationDiagnostic(
        String code,
        SeverityKind severity,
        UUID elementId,
        String feature,
        String message) {

    public ModelValidationDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(message, "message");
    }

    /*
     * The command-layer Diagnostic is intentionally not reused, with the same criterion as the generation contract:
     * it carries ErrorCode plus command scope/id/reason fields, which do not match the model validation shape
     * of code/severity/elementId/feature/message.
     */
}
