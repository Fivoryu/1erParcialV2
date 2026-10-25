package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.ConnectorPath;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.ElementGeometry;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.persistence.ConnectorPathRepository;
import com.diagramador.persistence.ElementGeometryRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Upserts classifier rectangles or replaces relationship paths in one shared diagram view. */
@Component
public class GeometrySetHandler implements CommandHandler.AddAction {

    private static final Set<String> FIELDS = Set.of("targetType", "viewId", "targetId", "x", "y", "w", "h",
            "zOrder", "waypoints");
    private static final Set<String> CLASSIFIER_FIELDS = Set.of("targetType", "viewId", "targetId", "x", "y", "w",
            "h", "zOrder");
    private static final Set<String> RELATIONSHIP_FIELDS = Set.of("targetType", "viewId", "targetId", "waypoints");

    private final CommandHandlerSupport support;
    private final ElementGeometryRepository geometryRepository;
    private final ConnectorPathRepository connectorPathRepository;

    public GeometrySetHandler(CommandHandlerSupport support, ElementGeometryRepository geometryRepository,
            ConnectorPathRepository connectorPathRepository) {
        this.support = support;
        this.geometryRepository = geometryRepository;
        this.connectorPathRepository = connectorPathRepository;
    }

    @Override
    public String type() {
        return "geometry.set";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        support.requireFields(payload, "targetType", "viewId", "targetId");
        TargetType targetType = targetType(payload);
        rejectUnknownFields(payload, targetType == TargetType.CLASSIFIER ? CLASSIFIER_FIELDS : RELATIONSHIP_FIELDS);

        UUID viewId = support.uuid(payload, "viewId");
        UUID targetId = support.uuid(payload, "targetId");
        DiagramView view = support.diagramView(context.model(), viewId);
        if (targetType == TargetType.CLASSIFIER) {
            support.classifier(context.model(), targetId);
            upsertClassifierGeometry(payload, view, targetId);
        } else {
            UmlRelationship relationship = support.relationship(context.model(), targetId);
            replaceRelationshipPaths(payload, view, relationship);
        }
        return Outcome.none();
    }

    private TargetType targetType(JsonNode payload) {
        String value = support.text(payload, "targetType");
        return switch (value.toUpperCase(java.util.Locale.ROOT)) {
            case "CLASSIFIER" -> TargetType.CLASSIFIER;
            case "RELATIONSHIP" -> TargetType.RELATIONSHIP;
            default -> {
                support.invalid("Unsupported geometry targetType '" + value
                        + "'; supported values are CLASSIFIER and RELATIONSHIP");
                yield null;
            }
        };
    }

    private void upsertClassifierGeometry(JsonNode payload, DiagramView view, UUID targetId) {
        support.requireFields(payload, "x", "y", "w", "h");
        int x = support.integer(payload, "x");
        int y = support.integer(payload, "y");
        int w = support.integer(payload, "w");
        int h = support.integer(payload, "h");
        if (w <= 0 || h <= 0) {
            support.invalid("Geometry dimensions must satisfy chk_geometry_dims: w and h must be greater than zero");
        }

        ElementGeometry geometry = geometryRepository.findByViewId(view.getId()).stream()
                .filter(candidate -> candidate.getTargetType() == TargetType.CLASSIFIER
                        && candidate.getTargetId().equals(targetId))
                .findFirst()
                .orElse(null);
        int zOrder = payload.hasNonNull("zOrder")
                ? support.integer(payload, "zOrder")
                : geometry == null ? 0 : geometry.getZOrder();
        if (geometry == null) {
            geometry = ElementGeometry.create(view, TargetType.CLASSIFIER, targetId, x, y, w, h, zOrder);
        } else {
            geometry.setX(x);
            geometry.setY(y);
            geometry.setW(w);
            geometry.setH(h);
            geometry.setZOrder(zOrder);
        }
        geometryRepository.save(geometry);
    }

    private void replaceRelationshipPaths(JsonNode payload, DiagramView view, UmlRelationship relationship) {
        support.requireFields(payload, "waypoints");
        List<Waypoint> waypoints = waypoints(payload.get("waypoints"));
        List<ConnectorPath> oldPaths = connectorPathRepository.findByRelationshipId(relationship.getId()).stream()
                .filter(path -> path.getView().getId().equals(view.getId()))
                .toList();
        connectorPathRepository.deleteAll(oldPaths);
        connectorPathRepository.flush();
        List<ConnectorPath> newPaths = new ArrayList<>();
        for (int ordinal = 0; ordinal < waypoints.size(); ordinal++) {
            Waypoint waypoint = waypoints.get(ordinal);
            newPaths.add(ConnectorPath.create(view, relationship, ordinal, waypoint.x(), waypoint.y()));
        }
        connectorPathRepository.saveAll(newPaths);
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
            rejectUnknownFields(point, Set.of("x", "y"));
            support.requireFields(point, "x", "y");
            result.add(new Waypoint(support.integer(point, "x"), support.integer(point, "y")));
        }
        return List.copyOf(result);
    }

    private void rejectUnknownFields(JsonNode payload, Set<String> allowed) {
        Set<String> unknown = new HashSet<>();
        payload.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            support.invalid("Payload contains unsupported field(s): " + unknown);
        }
    }

    private record Waypoint(int x, int y) {
    }
}
