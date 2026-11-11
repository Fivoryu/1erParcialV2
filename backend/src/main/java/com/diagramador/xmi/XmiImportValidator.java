package com.diagramador.xmi;

import com.diagramador.api.ModelResource;
import com.diagramador.command.ErrorCode;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.TargetType;
import com.diagramador.xmi.XmiImportIssue.Option;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The read-only checks an XMI import must pass, as a pure function of the parsed reading and (in AGREGAR mode) a
 * snapshot of the target model. The command handler rejects on the first blocker with the code and text it always
 * used; the dry run returns every issue. Both read the same list, so the two can never disagree.
 */
public class XmiImportValidator {

    /** Name and namespace of a classifier already stored in the target model. */
    public record ExistingClassifier(UUID id, UUID namespaceId, String name) {
    }

    /** What AGREGAR can collide with: stored classifiers and stored diagram names. */
    public record ExistingModel(List<ExistingClassifier> classifiers, Set<String> viewNames) {

        public static final ExistingModel EMPTY = new ExistingModel(List.of(), Set.of());

        public ExistingModel {
            classifiers = List.copyOf(classifiers);
            viewNames = Set.copyOf(viewNames);
        }
    }

    public List<XmiImportIssue> validate(XmiReadingResult reading, XmiImportMode mode, ExistingModel existing) {
        List<XmiImportIssue> issues = new ArrayList<>();
        validateReading(reading, issues);
        validateNames(reading.model(), issues);
        if (mode == XmiImportMode.AGREGAR && existing != null) {
            validateAgainstExisting(reading.model(), existing, issues);
        }
        return issues;
    }

    private void validateReading(XmiReadingResult reading, List<XmiImportIssue> issues) {
        ModelResource model = reading.model();
        if (model.views().size() != 1 || reading.diagrams().size() != 1) {
            int count = Math.max(model.views().size(), reading.diagrams().size());
            issues.add(XmiImportIssue.blocker("DIAGRAM_COUNT", "model", null,
                    "El archivo debe contener exactamente un diagrama y trae " + count + ".",
                    count > 1 ? List.of(Option.KEEP_VIEW) : List.of(), ErrorCode.PRECONDITION_FAILED,
                    "An XMI import must contain exactly one diagram view"));
        }
        Map<UUID, ModelResource.ClassifierResource> classifiers = new LinkedHashMap<>();
        for (ModelResource.ClassifierResource classifier : model.classifiers()) {
            if (classifier.id() == null || classifier.name() == null || classifier.name().isBlank()) {
                issues.add(XmiImportIssue.blocker("CLASSIFIER_INCOMPLETE", String.valueOf(classifier.id()),
                        classifier.name(), "Toda clase importada necesita un identificador y un nombre.", List.of(),
                        ErrorCode.INVALID_PAYLOAD, "Every imported classifier requires an id and a name"));
                continue;
            }
            if (classifiers.putIfAbsent(classifier.id(), classifier) != null) {
                issues.add(XmiImportIssue.blocker("DUPLICATE_CLASSIFIER_ID", classifier.id().toString(),
                        classifier.name(), "El archivo repite el identificador de la clase '" + classifier.name()
                                + "'.", List.of(), ErrorCode.INVALID_PAYLOAD,
                        "The imported model contains duplicate classifier id " + classifier.id()));
            }
        }
        for (ModelResource.ClassifierResource classifier : classifiers.values()) {
            if (classifier.namespaceId() != null) {
                ModelResource.ClassifierResource namespace = classifiers.get(classifier.namespaceId());
                if (namespace == null || namespace.kind() != ClassifierKind.PACKAGE) {
                    issues.add(XmiImportIssue.blocker("UNSUPPORTED_NAMESPACE", classifier.id().toString(),
                            classifier.name(), "La clase '" + classifier.name()
                                    + "' está dentro de algo que no es un paquete.", List.of(),
                            ErrorCode.PRECONDITION_FAILED,
                            "Classifier '" + classifier.name() + "' has an unsupported namespace"));
                }
            }
        }
        for (ModelResource.RelationshipResource relationship : model.relationships()) {
            validateRelationship(relationship, classifiers, issues);
        }
        if (model.views().isEmpty()) {
            return;
        }
        ModelResource.ViewResource view = model.views().get(0);
        if (view.id() == null || view.name() == null || view.name().isBlank()) {
            issues.add(XmiImportIssue.blocker("DIAGRAM_INCOMPLETE", String.valueOf(view.id()), view.name(),
                    "El diagrama importado necesita un identificador y un nombre.", List.of(),
                    ErrorCode.INVALID_PAYLOAD, "The imported diagram requires an id and a name"));
        }
        Set<String> geometryTargets = new HashSet<>();
        for (ModelResource.GeometryResource geometry : view.elementGeometry()) {
            if (geometry.id() == null || geometry.targetType() != TargetType.CLASSIFIER
                    || !classifiers.containsKey(geometry.targetId()) || geometry.w() <= 0 || geometry.h() <= 0) {
                issues.add(XmiImportIssue.blocker("INVALID_GEOMETRY", String.valueOf(geometry.targetId()), null,
                        "El diagrama contiene una figura con una clase inexistente o un tamaño no válido.",
                        List.of(), ErrorCode.PRECONDITION_FAILED,
                        "The imported diagram contains invalid classifier geometry"));
            }
            if (!geometryTargets.add(geometry.targetType() + ":" + geometry.targetId())) {
                issues.add(XmiImportIssue.blocker("DUPLICATE_GEOMETRY", String.valueOf(geometry.targetId()), null,
                        "El diagrama coloca dos veces la misma clase.", List.of(), ErrorCode.INVALID_PAYLOAD,
                        "The imported diagram contains duplicate geometry for a classifier"));
            }
        }
        for (ModelResource.ConnectorPathResource path : view.connectorPath()) {
            if (path.id() == null || model.relationships().stream()
                    .noneMatch(relationship -> Objects.equals(relationship.id(), path.relationshipId()))) {
                issues.add(XmiImportIssue.blocker("PATH_WITHOUT_RELATIONSHIP", String.valueOf(path.relationshipId()),
                        null, "El diagrama contiene un trazo de una relación inexistente.", List.of(),
                        ErrorCode.PRECONDITION_FAILED, "The imported diagram contains a path for a missing relationship"));
            }
        }
    }

    private void validateRelationship(ModelResource.RelationshipResource relationship,
            Map<UUID, ModelResource.ClassifierResource> classifiers, List<XmiImportIssue> issues) {
        try {
            RelationshipKind.valueOf(relationship.kind().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            issues.add(XmiImportIssue.blocker("UNSUPPORTED_RELATIONSHIP_KIND", String.valueOf(relationship.id()),
                    relationship.name(), "El tipo de relación '" + relationship.kind() + "' no está soportado.",
                    List.of(), ErrorCode.INVALID_PAYLOAD,
                    "Unsupported relationship kind '" + relationship.kind() + "'"));
            return;
        }
        if (relationship.id() == null || relationship.ends().size() != 2) {
            issues.add(XmiImportIssue.blocker("RELATIONSHIP_SHAPE", String.valueOf(relationship.id()),
                    relationship.name(), "Toda relación importada necesita un identificador y exactamente dos extremos.",
                    List.of(), ErrorCode.PRECONDITION_FAILED,
                    "Every imported relationship must have an id and exactly two ends"));
            return;
        }
        for (ModelResource.RelationshipEndResource end : relationship.ends()) {
            if (!classifiers.containsKey(end.classifierId())) {
                issues.add(XmiImportIssue.blocker("RELATIONSHIP_MISSING_CLASSIFIER", relationship.id().toString(),
                        relationship.name(), "Una relación apunta a una clase que no está en el archivo.",
                        List.of(), ErrorCode.PRECONDITION_FAILED,
                        "Relationship " + relationship.id() + " references a missing classifier"));
            }
            if (end.ordinal() != 1 && end.ordinal() != 2) {
                issues.add(XmiImportIssue.blocker("RELATIONSHIP_END_ORDINAL", relationship.id().toString(),
                        relationship.name(), "Los extremos de una relación deben numerarse 1 y 2.", List.of(),
                        ErrorCode.INVALID_PAYLOAD, "Relationship ends must have ordinal 1 or 2"));
            }
        }
    }

    private void validateNames(ModelResource model, List<XmiImportIssue> issues) {
        Set<NameKey> names = new HashSet<>();
        for (ModelResource.ClassifierResource classifier : model.classifiers()) {
            if (classifier.id() == null || classifier.name() == null || classifier.name().isBlank()) {
                continue;
            }
            if (!names.add(new NameKey(classifier.namespaceId(), classifier.name()))) {
                issues.add(XmiImportIssue.blocker("NAME_COLLISION_IN_FILE", classifier.id().toString(),
                        classifier.name(), "El archivo tiene dos clases llamadas '" + classifier.name()
                                + "' en el mismo paquete.", List.of(Option.RENAME, Option.SKIP),
                        ErrorCode.PRECONDITION_FAILED,
                        "Imported classifier name '" + classifier.name() + "' collides within its namespace"));
            }
        }
    }

    private void validateAgainstExisting(ModelResource imported, ExistingModel existing, List<XmiImportIssue> issues) {
        for (ModelResource.ClassifierResource candidate : imported.classifiers()) {
            ExistingClassifier collision = existing.classifiers().stream()
                    .filter(item -> Objects.equals(item.name(), candidate.name()))
                    .filter(item -> Objects.equals(item.namespaceId(), candidate.namespaceId()))
                    .findFirst().orElse(null);
            if (collision != null) {
                issues.add(XmiImportIssue.blocker("NAME_COLLISION", String.valueOf(candidate.id()), candidate.name(),
                        "Ya existe una clase llamada '" + candidate.name() + "' en el modelo.",
                        List.of(Option.RENAME, Option.SKIP), ErrorCode.PRECONDITION_FAILED,
                        "AGREGAR is blocked: classifier name '" + candidate.name()
                                + "' collides with existing classifier " + collision.id()));
            }
        }
        if (imported.views().isEmpty()) {
            return;
        }
        ModelResource.ViewResource view = imported.views().get(0);
        if (view.name() != null && existing.viewNames().contains(view.name())) {
            issues.add(XmiImportIssue.blocker("DIAGRAM_NAME_COLLISION", String.valueOf(view.id()), view.name(),
                    "Ya existe un diagrama llamado '" + view.name() + "' en el modelo.", List.of(Option.RENAME),
                    ErrorCode.PRECONDITION_FAILED,
                    "AGREGAR is blocked: diagram name '" + view.name() + "' already exists"));
        }
    }

    private record NameKey(UUID namespaceId, String name) {
    }
}
