package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.SeverityKind;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.UmlConstraint;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.persistence.UmlConstraintRepository;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ConstraintAddHandler implements CommandHandler.AddAction {

    private static final Set<String> FIELDS = Set.of("id", "ownerType", "ownerId", "expression", "severity");

    private final CommandHandlerSupport support;
    private final UmlConstraintRepository constraintRepository;

    public ConstraintAddHandler(CommandHandlerSupport support, UmlConstraintRepository constraintRepository) {
        this.support = support;
        this.constraintRepository = constraintRepository;
    }

    @Override
    public String type() {
        return "constraint.add";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        support.requireFields(payload, "id", "ownerType", "ownerId", "expression");
        UUID id = support.uuid(payload, "id");
        support.duplicateId(constraintRepository.existsById(id), id, "constraint");
        TargetType ownerType = support.ownerType(payload);
        UUID ownerId = support.uuid(payload, "ownerId");
        UUID targetOwnerId;
        if (ownerType == TargetType.CLASSIFIER) {
            Classifier owner = support.classifier(context, "classifier");
            targetOwnerId = owner.getId();
        } else {
            UmlRelationship owner = support.relationship(context, "relationship");
            targetOwnerId = owner.getId();
        }
        if (!targetOwnerId.equals(ownerId)) {
            support.precondition("Constraint ownerId " + ownerId + " does not match target "
                    + ownerType.name().toLowerCase(Locale.ROOT) + " " + targetOwnerId);
        }
        SeverityKind severity = severity(payload);
        support.insertConstraint(context, id, ownerType, ownerId, support.text(payload, "expression"), severity.name());
        return Outcome.none();
    }

    private SeverityKind severity(JsonNode payload) {
        String value = payload.has("severity") && !payload.get("severity").isNull()
                ? support.text(payload, "severity").toUpperCase(Locale.ROOT) : "ERROR";
        try {
            return SeverityKind.valueOf(value);
        } catch (IllegalArgumentException exception) {
            support.invalid("Unsupported constraint severity '" + value + "'");
            return null;
        }
    }
}
