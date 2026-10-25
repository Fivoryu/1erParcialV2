package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.persistence.RelationshipEndRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Updates relationship metadata and keeps the aggregation marker derived from its kind. */
@Component
public class RelationshipUpdateHandler implements CommandHandler.AddAction {

    private static final Set<String> FIELDS = Set.of("name", "kind", "stereotype", "associationClassId", "source", "target");
    private static final Set<String> END_FIELDS = Set.of("role", "multiplicity", "navigability");

    private final CommandHandlerSupport support;
    private final RelationshipEndRepository endRepository;

    public RelationshipUpdateHandler(CommandHandlerSupport support, RelationshipEndRepository endRepository) {
        this.support = support;
        this.endRepository = endRepository;
    }

    @Override
    public String type() {
        return "relationship.update";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        if (payload.isEmpty()) {
            support.invalid("At least one relationship field must be supplied for update");
        }

        UmlRelationship relationship = support.relationship(context, "relationship");
        List<RelationshipEnd> ends = endRepository.findByRelationshipId(relationship.getId());
        RelationshipEnd source = endByOrdinal(ends, (short) 1, relationship);
        RelationshipEnd target = endByOrdinal(ends, (short) 2, relationship);

        if (payload.has("name")) {
            relationship.setName(support.optionalText(payload, "name"));
        }
        RelationshipKind kind = support.relationshipKind(payload, "kind", relationship.getKind());
        updateEnd(payload, "source", source);
        updateEnd(payload, "target", target);
        relationship.setKind(kind);
        if (payload.has("stereotype")) {
            relationship.setStereotype(support.dependencyStereotype(payload, "stereotype", kind));
        } else if (kind != RelationshipKind.DEPENDENCY) {
            // Leaving the dependency kind drops the stereotype with it: nothing else may carry one.
            relationship.setStereotype(null);
        }
        if (payload.has("associationClassId")) {
            relationship.setAssociationClassId(support.associationClassId(payload, "associationClassId",
                    context.model(), kind, source.getClassifier().getId(), target.getClassifier().getId(),
                    relationship.getId()));
        } else if (kind != RelationshipKind.ASSOCIATION && kind != RelationshipKind.AGGREGATION
                && kind != RelationshipKind.COMPOSITION) {
            // Only an association has an association class, so leaving the association kinds releases it.
            relationship.setAssociationClassId(null);
        }
        CommandHandlerSupport.AggregationKinds aggregation = support.aggregationKinds(kind);
        source.setAggregationKind(aggregation.source());
        target.setAggregationKind(aggregation.target());
        return Outcome.none();
    }

    private RelationshipEnd endByOrdinal(List<RelationshipEnd> ends, short ordinal, UmlRelationship relationship) {
        if (ends.size() != 2) {
            support.precondition("Relationship " + relationship.getId() + " must have exactly two ends");
        }
        return ends.stream()
                .filter(end -> end.getOrdinal() == ordinal)
                .findFirst()
                .orElseThrow(() -> new com.diagramador.command.CommandValidationException(
                        com.diagramador.command.ErrorCode.PRECONDITION_FAILED,
                        "Relationship " + relationship.getId() + " is missing end ordinal " + ordinal));
    }

    private void updateEnd(JsonNode payload, String field, RelationshipEnd end) {
        if (!payload.has(field)) {
            return;
        }
        JsonNode update = payload.get(field);
        if (update == null || !update.isObject()) {
            support.invalid("Payload field '" + field + "' must be an object");
        }
        rejectUnknownFields(update, field);
        if (update.isEmpty()) {
            support.invalid("Payload field '" + field + "' must contain at least one end property");
        }
        if (update.has("role")) {
            end.setRole(support.optionalText(update, "role"));
        }
        if (update.has("multiplicity")) {
            end.setMultiplicity(support.multiplicity(update, "multiplicity", end.getMultiplicity()));
        }
        if (update.has("navigability")) {
            end.setNavigability(navigability(update, "navigability", end.getNavigability()));
        }
    }

    private NavigationKind navigability(JsonNode payload, String field, NavigationKind defaultValue) {
        String value = payload.has(field) && !payload.get(field).isNull()
                ? support.text(payload, field).toUpperCase(Locale.ROOT)
                : defaultValue.name();
        try {
            return NavigationKind.valueOf(value);
        } catch (IllegalArgumentException exception) {
            support.invalid("Unsupported navigability '" + value + "'");
            return null;
        }
    }

    private void rejectUnknownFields(JsonNode payload, String field) {
        Set<String> unknown = new HashSet<>();
        payload.fieldNames().forEachRemaining(name -> {
            if (!END_FIELDS.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            support.invalid("Payload field '" + field + "' contains unsupported field(s): " + unknown);
        }
    }
}
