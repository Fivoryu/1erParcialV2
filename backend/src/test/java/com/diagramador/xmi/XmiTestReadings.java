package com.diagramador.xmi;

import com.diagramador.api.ModelResource;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.TargetType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Small in-memory readings for the pure import checks, built like XmiImportServiceTest builds its models. */
final class XmiTestReadings {

    private XmiTestReadings() {
    }

    static UUID id(long value) {
        return UUID.fromString("00000000-0000-0000-0000-%012x".formatted(value));
    }

    static ModelResource.ClassifierResource classifier(long id, String name, ClassifierKind kind, Long namespace) {
        return new ModelResource.ClassifierResource(id(id), name, kind, false, namespace == null ? null : id(namespace),
                List.of(), List.of(), List.of());
    }

    static ModelResource.ClassifierResource clazz(long id, String name) {
        return classifier(id, name, ClassifierKind.CLASS, null);
    }

    static ModelResource.RelationshipResource association(long id, long source, long target) {
        return new ModelResource.RelationshipResource(id(id), "ASSOCIATION", "r" + id,
                List.of(end(id * 10 + 1, source, 1), end(id * 10 + 2, target, 2)), List.of());
    }

    static ModelResource.RelationshipEndResource end(long id, long classifier, int ordinal) {
        return new ModelResource.RelationshipEndResource(id(id), id(classifier), null,
                MultiplicityKind.EXACTLY_ONE, NavigationKind.UNSPECIFIED, "none", (short) ordinal);
    }

    static ModelResource.GeometryResource geometry(long id, long classifier, int w, int h) {
        return new ModelResource.GeometryResource(id(id), TargetType.CLASSIFIER, id(classifier), 10, 20, w, h, 0);
    }

    static ModelResource.ViewResource view(long id, String name, boolean isDefault,
            List<ModelResource.GeometryResource> geometry, List<ModelResource.ConnectorPathResource> paths) {
        return new ModelResource.ViewResource(id(id), name, isDefault, geometry, paths);
    }

    static XmiReadingResult.Diagram diagram(ModelResource.ViewResource view,
            List<XmiReadingResult.FigurePlacement> figures, List<XmiReadingResult.ConnectorPlacement> connectors) {
        return new XmiReadingResult.Diagram(view.id(), view.name(), view.isDefault(), figures, connectors);
    }

    static XmiReadingResult.FigurePlacement figure(long subject, long classifier) {
        return new XmiReadingResult.FigurePlacement("fig" + subject, id(classifier), "duid" + subject, 10, 20, 210,
                120, 0);
    }

    static XmiReadingResult.ConnectorPlacement connector(long subject, long relationship) {
        return new XmiReadingResult.ConnectorPlacement("con" + subject, id(relationship), "3", null, null, null,
                null, null);
    }

    /** Two classes with a relationship and one fully consistent view. */
    static XmiReadingResult clean() {
        return reading(List.of(clazz(1, "Alpha"), clazz(2, "Beta")), List.of(association(10, 1, 2)),
                List.of(view(100, "Main", true, List.of(geometry(500, 1, 200, 100), geometry(501, 2, 200, 100)),
                        List.of())),
                List.of());
    }

    static XmiReadingResult reading(List<ModelResource.ClassifierResource> classifiers,
            List<ModelResource.RelationshipResource> relationships, List<ModelResource.ViewResource> views,
            List<XmiReadingResult.Diagram> diagrams) {
        List<XmiReadingResult.Diagram> effective = new ArrayList<>(diagrams);
        if (effective.isEmpty()) {
            for (ModelResource.ViewResource view : views) {
                effective.add(diagram(view, List.of(), List.of()));
            }
        }
        return new XmiReadingResult(new ModelResource(id(900), id(901), 1, id(902), views, classifiers, relationships),
                effective, Map.of(), List.of(), List.of());
    }
}
