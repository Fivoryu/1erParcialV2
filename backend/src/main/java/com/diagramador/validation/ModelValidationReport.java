package com.diagramador.validation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * API resource returned by GET /projects/{projectId}/model/validation: the semantic diagnostics of
 * the project's canonical UML model, empty when the model is well formed or not yet defined.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ModelValidationReport(List<ModelValidationDiagnostic> diagnostics) {

    public ModelValidationReport {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
