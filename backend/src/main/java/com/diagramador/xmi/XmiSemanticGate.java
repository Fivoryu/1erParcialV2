package com.diagramador.xmi;

import com.diagramador.domain.SeverityKind;
import com.diagramador.validation.ModelResourceValidationAdapter;
import com.diagramador.validation.ModelValidationDiagnostic;
import com.diagramador.validation.ModelValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The semantic "the import must end clean" gate: the model rules run over the model an import would leave behind.
 * Shared by the import service (dry run and submission) and the command handler so every route into the handler
 * applies the same verdict.
 */
public class XmiSemanticGate {

    /**
     * Semantic rules about whether the model can feed the backend generator, not about whether the file is a sound
     * model: EA exports routinely leave attributes untyped and use many-to-many associations, so these stay visible
     * as warnings and do not stop an import.
     */
    private static final Set<String> GENERATION_READINESS_RULES = Set.of(ModelValidator.UNRESOLVED_TYPE,
            ModelValidator.UNSUPPORTED_MULTIPLICITY_COMBINATION);

    private final ModelValidator modelValidator = new ModelValidator();

    /** The semantic model rules over what the import would leave behind; a model they cannot read has no verdict. */
    public List<XmiImportIssue> issues(XmiReadingResult reading) {
        List<ModelValidationDiagnostic> diagnostics;
        try {
            diagnostics = modelValidator.validate(ModelResourceValidationAdapter.toReadModel(reading.model()));
        } catch (RuntimeException exception) {
            return List.of();
        }
        List<XmiImportIssue> issues = new ArrayList<>();
        for (ModelValidationDiagnostic diagnostic : diagnostics) {
            String subject = diagnostic.elementId() == null ? null : diagnostic.elementId().toString();
            String message = "El modelo importado incumple la regla " + diagnostic.code() + " ("
                    + diagnostic.feature() + "): " + diagnostic.message();
            boolean blocks = diagnostic.severity() == SeverityKind.ERROR
                    && !GENERATION_READINESS_RULES.contains(diagnostic.code());
            issues.add(blocks
                    ? XmiImportIssue.modelBlocker(diagnostic.code(), subject, diagnostic.feature(), message)
                    : XmiImportIssue.warning(diagnostic.code(), subject, diagnostic.feature(), message));
        }
        return issues;
    }
}
