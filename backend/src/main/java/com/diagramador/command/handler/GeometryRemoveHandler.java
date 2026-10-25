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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Removes the selected classifier rectangle or relationship path from one shared view. */
@Component
public class GeometryRemoveHandler implements CommandHandler.DeleteAction {

    private static final Set<String> FIELDS = Set.of("targetType", "viewId", "targetId");

    private final CommandHandlerSupport support;
    private final ElementGeometryRepository geometryRepository;
    private final ConnectorPathRepository connectorPathRepository;

    public GeometryRemoveHandler(CommandHandlerSupport support, ElementGeometryRepository geometryRepository,
            ConnectorPathRepository connectorPathRepository) {
        this.support = support;
        this.geometryRepository = geometryRepository;
        this.connectorPathRepository = connectorPathRepository;
    }

    @Override
    public String type() {
        return "geometry.remove";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        support.requireFields(payload, "targetType", "viewId", "targetId");
        TargetType targetType = targetType(payload);
        rejectUnknownFields(payload);

        UUID viewId = support.uuid(payload, "viewId");
        UUID targetId = support.uuid(payload, "targetId");
        DiagramView view = support.diagramView(context.model(), viewId);
        if (targetType == TargetType.CLASSIFIER) {
            support.classifier(context.model(), targetId);
            List<ElementGeometry> geometries = geometryRepository.findByViewId(view.getId()).stream()
                    .filter(geometry -> geometry.getTargetType() == TargetType.CLASSIFIER
                            && geometry.getTargetId().equals(targetId))
                    .toList();
            geometryRepository.deleteAll(geometries);
        } else {
            UmlRelationship relationship = support.relationship(context.model(), targetId);
            List<ConnectorPath> paths = connectorPathRepository.findByRelationshipId(relationship.getId()).stream()
                    .filter(path -> path.getView().getId().equals(view.getId()))
                    .toList();
            connectorPathRepository.deleteAll(paths);
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

    private void rejectUnknownFields(JsonNode payload) {
        Set<String> unknown = new HashSet<>();
        payload.fieldNames().forEachRemaining(name -> {
            if (!FIELDS.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            support.invalid("Payload contains unsupported field(s): " + unknown);
        }
    }
}
