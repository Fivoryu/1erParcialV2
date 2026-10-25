package com.diagramador.vcs;

import com.diagramador.api.ModelResource;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/** Canonical model state stored by a version-control snapshot. */
public record SnapshotModel(
        List<SnapshotView> views,
        List<ModelResource.ClassifierResource> classifiers,
        List<ModelResource.RelationshipResource> relationships,
        List<SnapshotViewStyle> viewStyle) {

    public SnapshotModel {
        views = views == null ? List.of() : List.copyOf(views);
        classifiers = classifiers == null ? List.of() : List.copyOf(classifiers);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
        viewStyle = viewStyle == null ? List.of() : List.copyOf(viewStyle);
    }

    public record SnapshotView(UUID id, String name, boolean isDefault, String viewKind,
            List<ModelResource.GeometryResource> elementGeometry,
            List<ModelResource.ConnectorPathResource> connectorPath) {

        public SnapshotView {
            elementGeometry = elementGeometry == null ? List.of() : List.copyOf(elementGeometry);
            connectorPath = connectorPath == null ? List.of() : List.copyOf(connectorPath);
        }
    }

    public record SnapshotViewStyle(UUID viewId, UUID targetId, String routing, int cornerRadius,
            int strokeWidth, boolean dashed, JsonNode routeAdjustment) {
    }
}
