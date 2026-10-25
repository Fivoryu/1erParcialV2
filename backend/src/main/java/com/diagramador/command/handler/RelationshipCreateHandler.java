package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.command.Target;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ConnectorPath;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.persistence.ConnectorPathRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Creates one UML relationship, its source/target ends, and optional default-view waypoints. */
@Component
public class RelationshipCreateHandler implements CommandHandler.AddAction {

    private static final Set<String> FIELDS = Set.of("id", "kind", "name", "stereotype", "source", "target", "waypoints");
    private static final Set<String> END_FIELDS = Set.of("classifierId", "role", "multiplicity", "navigability");

    private final CommandHandlerSupport support;
    private final UmlRelationshipRepository relationshipRepository;
    private final RelationshipEndRepository endRepository;
    private final ConnectorPathRepository connectorPathRepository;

    public RelationshipCreateHandler(CommandHandlerSupport support, UmlRelationshipRepository relationshipRepository,
            RelationshipEndRepository endRepository, ConnectorPathRepository connectorPathRepository) {
        this.support = support;
        this.relationshipRepository = relationshipRepository;
        this.endRepository = endRepository;
        this.connectorPathRepository = connectorPathRepository;
    }

    @Override
    public String type() {
        return "relationship.create";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        support.requireFields(payload, "id", "kind", "source", "target");

        UUID id = support.uuid(payload, "id");
        RelationshipKind kind = support.relationshipKind(payload, "kind", RelationshipKind.ASSOCIATION);
        support.duplicateId(relationshipRepository.existsById(id), id, "relationship");

        EndInput source = endInput(context, payload.get("source"), "source");
        EndInput target = endInput(context, payload.get("target"), "target");
        validateEnvelopeTargets(context, source.classifier().getId(), target.classifier().getId());

        List<Waypoint> waypoints = payload.has("waypoints") ? waypoints(payload.get("waypoints")) : List.of();
        DiagramView defaultView = payload.has("waypoints") ? support.defaultDiagramView(context.model()) : null;
        String name = support.optionalText(payload, "name");
        String stereotype = support.dependencyStereotype(payload, "stereotype", kind);

        support.insertRelationship(context, id, kind, name, stereotype);
        context.entityManager().flush();
        UmlRelationship relationship = relationshipRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Created relationship " + id + " cannot be reloaded"));

        CommandHandlerSupport.AggregationKinds aggregation = support.aggregationKinds(kind);
        endRepository.saveAll(List.of(
                RelationshipEnd.create(relationship, source.classifier(), (short) 1, source.role(),
                        source.multiplicity(), source.navigability(), aggregation.source()),
                RelationshipEnd.create(relationship, target.classifier(), (short) 2, target.role(),
                        target.multiplicity(), target.navigability(), aggregation.target())));

        List<ConnectorPath> paths = new ArrayList<>();
        for (int ordinal = 0; ordinal < waypoints.size(); ordinal++) {
            Waypoint waypoint = waypoints.get(ordinal);
            paths.add(ConnectorPath.create(defaultView, relationship, ordinal, waypoint.x(), waypoint.y()));
        }
        connectorPathRepository.saveAll(paths);
        return Outcome.none();
    }

    private EndInput endInput(Context context, JsonNode end, String name) {
        if (end == null || !end.isObject()) {
            support.invalid("Payload field '" + name + "' must be an object");
        }
        rejectUnknownFields(end, END_FIELDS, name);
        support.requireFields(end, "classifierId");
        UUID classifierId = support.uuid(end, "classifierId");
        Classifier classifier = support.classifier(context.model(), classifierId);
        String role = support.optionalText(end, "role");
        MultiplicityKind multiplicity = support.multiplicity(end, "multiplicity", MultiplicityKind.EXACTLY_ONE);
        NavigationKind navigability = navigability(end, "navigability", NavigationKind.UNSPECIFIED);
        return new EndInput(classifier, role, multiplicity, navigability);
    }

    private void validateEnvelopeTargets(Context context, UUID sourceId, UUID targetId) {
        List<Target> targets = context.envelope().targets();
        if (targets.size() != 2 || targets.get(0) == null || targets.get(1) == null
                || targets.get(0).id() == null || targets.get(1).id() == null) {
            support.invalid("relationship.create requires exactly two classifier targets for source and target");
        }
        if (!isClassifierTarget(targets.get(0)) || !isClassifierTarget(targets.get(1))) {
            support.invalid("relationship.create targets must both have scope classifier");
        }
        if (!sourceId.equals(targets.get(0).id()) || !targetId.equals(targets.get(1).id())) {
            support.invalid("relationship.create classifier targets must match source and target in order");
        }
    }

    private boolean isClassifierTarget(Target target) {
        return target.scope() != null && target.scope().equalsIgnoreCase("classifier");
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

    private List<Waypoint> waypoints(JsonNode value) {
        if (value == null || !value.isArray()) {
            support.invalid("Payload field 'waypoints' must be a list of {x, y} points");
        }
        List<Waypoint> result = new ArrayList<>();
        for (int index = 0; index < value.size(); index++) {
            JsonNode point = value.get(index);
            if (!point.isObject()) {
                support.invalid("Waypoint " + index + " must be an object with x and y");
            }
            rejectUnknownFields(point, Set.of("x", "y"), "waypoints[" + index + "]");
            support.requireFields(point, "x", "y");
            result.add(new Waypoint(support.integer(point, "x"), support.integer(point, "y")));
        }
        return List.copyOf(result);
    }

    private void rejectUnknownFields(JsonNode payload, Set<String> allowed, String field) {
        Set<String> unknown = new HashSet<>();
        payload.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            support.invalid("Payload field '" + field + "' contains unsupported field(s): " + unknown);
        }
    }

    private record EndInput(Classifier classifier, String role, MultiplicityKind multiplicity,
            NavigationKind navigability) {
    }

    private record Waypoint(int x, int y) {
    }
}
