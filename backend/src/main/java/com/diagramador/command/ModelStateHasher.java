package com.diagramador.command;

import com.diagramador.api.ModelResource;
import com.diagramador.api.ModelResource.ConstraintResource;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ConnectorPath;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.ElementGeometry;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.PersistenceStore;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlConstraint;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UmlOperation;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.domain.TargetType;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.ConnectorPathRepository;
import com.diagramador.persistence.DiagramViewRepository;
import com.diagramador.persistence.ElementGeometryRepository;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import com.diagramador.persistence.ViewStyle;
import com.diagramador.persistence.ViewStyleRepository;
import com.diagramador.vcs.SnapshotModel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Produces the stable state hash used by model versions. */
@Component
public class ModelStateHasher {

    private final ClassifierRepository classifierRepository;
    private final UmlAttributeRepository attributeRepository;
    private final UmlOperationRepository operationRepository;
    private final OperationParameterRepository parameterRepository;
    private final UmlRelationshipRepository relationshipRepository;
    private final RelationshipEndRepository relationshipEndRepository;
    private final UmlConstraintRepository constraintRepository;
    private final DiagramViewRepository viewRepository;
    private final ElementGeometryRepository geometryRepository;
    private final ConnectorPathRepository connectorPathRepository;
    private final ViewStyleRepository viewStyleRepository;

    public ModelStateHasher(ClassifierRepository classifierRepository, UmlAttributeRepository attributeRepository,
            UmlOperationRepository operationRepository, OperationParameterRepository parameterRepository,
            UmlRelationshipRepository relationshipRepository, RelationshipEndRepository relationshipEndRepository,
            UmlConstraintRepository constraintRepository, DiagramViewRepository viewRepository,
            ElementGeometryRepository geometryRepository, ConnectorPathRepository connectorPathRepository,
            ViewStyleRepository viewStyleRepository) {
        this.classifierRepository = classifierRepository;
        this.attributeRepository = attributeRepository;
        this.operationRepository = operationRepository;
        this.parameterRepository = parameterRepository;
        this.relationshipRepository = relationshipRepository;
        this.relationshipEndRepository = relationshipEndRepository;
        this.constraintRepository = constraintRepository;
        this.viewRepository = viewRepository;
        this.geometryRepository = geometryRepository;
        this.connectorPathRepository = connectorPathRepository;
        this.viewStyleRepository = viewStyleRepository;
    }

    @Transactional(readOnly = true)
    public String hash(UmlModel model) {
        UUID modelId = model.getId();
        List<String> lines = new ArrayList<>();
        List<Classifier> classifiers = classifierRepository.findByModelId(modelId);
        List<UmlAttribute> attributes = new ArrayList<>();
        List<UmlOperation> operations = new ArrayList<>();
        List<OperationParameter> parameters = new ArrayList<>();
        for (Classifier classifier : classifiers) {
            attributes.addAll(attributeRepository.findByClassifierId(classifier.getId()));
            List<UmlOperation> classifierOperations = operationRepository.findByClassifierId(classifier.getId());
            operations.addAll(classifierOperations);
            for (UmlOperation operation : classifierOperations) {
                parameters.addAll(parameterRepository.findByOperationId(operation.getId()));
            }
        }
        List<UmlRelationship> relationships = relationshipRepository.findByModelId(modelId);
        List<RelationshipEnd> relationshipEnds = new ArrayList<>();
        for (UmlRelationship relationship : relationships) {
            relationshipEnds.addAll(relationshipEndRepository.findByRelationshipId(relationship.getId()));
        }
        List<DiagramView> views = viewRepository.findByModelId(modelId);
        List<ConnectorPath> connectorPaths = new ArrayList<>();
        List<ViewStyle> viewStyles = new ArrayList<>();
        for (DiagramView view : views) {
            connectorPaths.addAll(connectorPathRepository.findByViewId(view.getId()));
            viewStyles.addAll(viewStyleRepository.findByViewId(view.getId()));
        }

        classifiers.stream().sorted(byId(Classifier::getId))
                .map(classifier -> withStore(line("classifiers", classifier.getId(),
                        field("namespaceId", id(classifier.getNamespace())), field("kind", classifier.getKind()),
                        field("name", classifier.getName()), field("isAbstract", classifier.isAbstract())),
                        classifier.getStoreOverride()))
                .forEach(lines::add);
        attributes.stream().sorted(byId(UmlAttribute::getId))
                .map(attribute -> line("attributes", attribute.getId(),
                        field("classifierId", id(attribute.getClassifier())),
                        field("typeReferenceId", id(attribute.getTypeReference())), field("name", attribute.getName()),
                        field("kind", attribute.getKind()), field("visibility", attribute.getVisibility()),
                        field("multiplicity", attribute.getMultiplicity()), field("defaultValue", attribute.getDefaultValue()),
                        field("ordinal", attribute.getOrdinal())))
                .forEach(lines::add);
        operations.stream().sorted(byId(UmlOperation::getId))
                .map(operation -> line("operations", operation.getId(),
                        field("classifierId", id(operation.getClassifier())), field("name", operation.getName()),
                        field("returnTypeReferenceId", id(operation.getReturnTypeReference())),
                        field("visibility", operation.getVisibility()), field("isAbstract", operation.isAbstract()),
                        field("isStatic", operation.isStatic()), field("ordinal", operation.getOrdinal())))
                .forEach(lines::add);
        parameters.stream().sorted(byId(OperationParameter::getId))
                .map(parameter -> line("parameters", parameter.getId(),
                        field("operationId", id(parameter.getOperation())),
                        field("typeReferenceId", id(parameter.getTypeReference())), field("name", parameter.getName()),
                        field("direction", parameter.getDirection()), field("ordinal", parameter.getOrdinal())))
                .forEach(lines::add);
        relationships.stream().sorted(byId(UmlRelationship::getId))
                .map(relationship -> withStereotype(line("relationships", relationship.getId(),
                        field("kind", relationship.getKind()), field("name", relationship.getName())),
                        relationship.getStereotype()))
                .forEach(lines::add);
        relationshipEnds.stream().sorted(byId(RelationshipEnd::getId))
                .map(end -> line("relationshipEnds", end.getId(), field("relationshipId", id(end.getRelationship())),
                        field("classifierId", id(end.getClassifier())), field("ordinal", end.getOrdinal()),
                        field("role", end.getRole()), field("multiplicity", end.getMultiplicity()),
                        field("navigability", end.getNavigability()), field("aggregationKind", end.getAggregationKind())))
                .forEach(lines::add);
        constraintRepository.findByModelId(modelId).stream().sorted(byId(UmlConstraint::getId))
                .map(constraint -> line("constraints", constraint.getId(), field("ownerType", constraint.getOwnerType()),
                        field("ownerId", constraint.getOwnerId()), field("expression", constraint.getExpression()),
                        field("severity", constraint.getSeverity())))
                .forEach(lines::add);
        views.stream().sorted(byId(DiagramView::getId))
                .map(view -> line("views", view.getId(), field("name", view.getName()),
                        field("viewKind", view.getViewKind()), field("isDefault", view.isDefault())))
                .forEach(lines::add);
        geometryRepository.findByViewModelId(modelId).stream().sorted(byId(ElementGeometry::getId))
                .map(geometry -> line("geometries", geometry.getId(), field("viewId", id(geometry.getView())),
                        field("targetType", geometry.getTargetType()), field("targetId", geometry.getTargetId()),
                        field("x", geometry.getX()), field("y", geometry.getY()), field("w", geometry.getW()),
                        field("h", geometry.getH()), field("zOrder", geometry.getZOrder())))
                .forEach(lines::add);
        connectorPaths.stream().sorted(byId(ConnectorPath::getId))
                .map(path -> line("connectorPaths", path.getId(), field("viewId", id(path.getView())),
                        field("relationshipId", id(path.getRelationship())), field("ordinal", path.getOrdinal()),
                        field("x", path.getX()), field("y", path.getY())))
                .forEach(lines::add);
        viewStyles.stream()
                .sorted(Comparator.comparing((ViewStyle style) -> style.getView().getId().toString())
                        .thenComparing(style -> style.getTargetId().toString()))
                .map(style -> line("viewStyles", style.getView().getId(), field("targetId", style.getTargetId()),
                        field("routing", style.getRouting()), field("cornerRadius", style.getCornerRadius()),
                        field("strokeWidth", style.getStrokeWidth()), field("dashed", style.isDashed()),
                        field("routeAdjustment", style.getRouteAdjustment())))
                .forEach(lines::add);

        String canonicalProjection = String.join("\n", lines);
        return sha256(canonicalProjection);
    }

    /** Hashes a frozen snapshot using the same canonical projection as a persisted model. */
    public String hash(SnapshotModel snapshot) {
        List<String> lines = new ArrayList<>();
        List<ModelResource.ClassifierResource> classifiers = snapshot.classifiers().stream()
                .sorted(Comparator.comparing(value -> value.id().toString())).toList();
        classifiers.stream()
                .map(classifier -> withStore(line("classifiers", classifier.id(),
                        field("namespaceId", classifier.namespaceId()), field("kind", classifier.kind()),
                        field("name", classifier.name()), field("isAbstract", classifier.isAbstract())),
                        classifier.storeOverride()))
                .forEach(lines::add);
        classifiers.stream().flatMap(classifier -> classifier.attributes().stream())
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .map(attribute -> line("attributes", attribute.id(),
                        field("classifierId", ownerId(classifiers, attribute.id())),
                        field("typeReferenceId", attribute.typeReferenceId()), field("name", attribute.name()),
                        field("kind", attribute.kind()), field("visibility", attribute.visibility()),
                        field("multiplicity", attribute.multiplicity()), field("defaultValue", attribute.defaultValue()),
                        field("ordinal", attribute.ordinal())))
                .forEach(lines::add);
        classifiers.stream().flatMap(classifier -> classifier.operations().stream())
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .map(operation -> line("operations", operation.id(),
                        field("classifierId", ownerIdForOperation(classifiers, operation.id())),
                        field("name", operation.name()), field("returnTypeReferenceId", operation.returnTypeReferenceId()),
                        field("visibility", operation.visibility()), field("isAbstract", operation.isAbstract()),
                        field("isStatic", operation.isStatic()), field("ordinal", operation.ordinal())))
                .forEach(lines::add);
        classifiers.stream().flatMap(classifier -> classifier.operations().stream())
                .flatMap(operation -> operation.parameters().stream())
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .map(parameter -> line("parameters", parameter.id(),
                        field("operationId", ownerIdForParameter(classifiers, parameter.id())),
                        field("typeReferenceId", parameter.typeReferenceId()), field("name", parameter.name()),
                        field("direction", parameter.direction()), field("ordinal", parameter.ordinal())))
                .forEach(lines::add);
        List<ModelResource.RelationshipResource> relationships = snapshot.relationships().stream()
                .sorted(Comparator.comparing(value -> value.id().toString())).toList();
        relationships.stream()
                .map(relationship -> withStereotype(line("relationships", relationship.id(),
                        field("kind", relationship.kind()), field("name", relationship.name())),
                        relationship.stereotype()))
                .forEach(lines::add);
        relationships.stream().flatMap(relationship -> relationship.ends().stream())
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .map(end -> line("relationshipEnds", end.id(),
                        field("relationshipId", relationshipIdForEnd(relationships, end.id())),
                        field("classifierId", end.classifierId()), field("ordinal", end.ordinal()),
                        field("role", end.role()), field("multiplicity", end.multiplicity()),
                        field("navigability", end.navigability()), field("aggregationKind", end.aggregationKind())))
                .forEach(lines::add);
        java.util.stream.Stream.concat(classifiers.stream().flatMap(value -> value.constraints().stream()),
                        relationships.stream().flatMap(value -> value.constraints().stream()))
                .sorted(Comparator.comparing(ConstraintResource::id, Comparator.comparing(UUID::toString)))
                .map(constraint -> {
                    ConstraintOwner owner = constraintOwner(snapshot, constraint.id());
                    return line("constraints", constraint.id(), field("ownerType", owner.type()),
                            field("ownerId", owner.id()), field("expression", constraint.expression()),
                            field("severity", constraint.severity()));
                })
                .forEach(lines::add);
        List<SnapshotModel.SnapshotView> views = snapshot.views().stream()
                .sorted(Comparator.comparing(value -> value.id().toString())).toList();
        views.stream().map(view -> line("views", view.id(), field("name", view.name()),
                field("viewKind", view.viewKind()), field("isDefault", view.isDefault())))
                .forEach(lines::add);
        views.stream().flatMap(view -> view.elementGeometry().stream())
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .map(geometry -> line("geometries", geometry.id(),
                        field("viewId", viewIdForGeometry(views, geometry.id())),
                        field("targetType", geometry.targetType()), field("targetId", geometry.targetId()),
                        field("x", geometry.x()), field("y", geometry.y()), field("w", geometry.w()),
                        field("h", geometry.h()), field("zOrder", geometry.zOrder())))
                .forEach(lines::add);
        views.stream().flatMap(view -> view.connectorPath().stream())
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .map(path -> line("connectorPaths", path.id(),
                        field("viewId", viewIdForPath(views, path.id())),
                        field("relationshipId", path.relationshipId()), field("ordinal", path.ordinal()),
                        field("x", path.x()), field("y", path.y())))
                .forEach(lines::add);
        snapshot.viewStyle().stream()
                .sorted(Comparator.comparing((SnapshotModel.SnapshotViewStyle style) -> style.viewId().toString())
                        .thenComparing(style -> style.targetId().toString()))
                .map(style -> line("viewStyles", style.viewId(), field("targetId", style.targetId()),
                        field("routing", style.routing()), field("cornerRadius", style.cornerRadius()),
                        field("strokeWidth", style.strokeWidth()), field("dashed", style.dashed()),
                        field("routeAdjustment", style.routeAdjustment())))
                .forEach(lines::add);
        return sha256(String.join("\n", lines));
    }

    private UUID ownerId(List<ModelResource.ClassifierResource> classifiers, UUID attributeId) {
        return classifiers.stream().filter(classifier -> classifier.attributes().stream()
                .anyMatch(attribute -> attribute.id().equals(attributeId))).map(ModelResource.ClassifierResource::id)
                .findFirst().orElse(null);
    }

    private UUID ownerIdForOperation(List<ModelResource.ClassifierResource> classifiers, UUID operationId) {
        return classifiers.stream().filter(classifier -> classifier.operations().stream()
                .anyMatch(operation -> operation.id().equals(operationId))).map(ModelResource.ClassifierResource::id)
                .findFirst().orElse(null);
    }

    private UUID ownerIdForParameter(List<ModelResource.ClassifierResource> classifiers, UUID parameterId) {
        return classifiers.stream().flatMap(classifier -> classifier.operations().stream())
                .filter(operation -> operation.parameters().stream().anyMatch(parameter -> parameter.id().equals(parameterId)))
                .map(ModelResource.OperationResource::id).findFirst().orElse(null);
    }

    private UUID relationshipIdForEnd(List<ModelResource.RelationshipResource> relationships, UUID endId) {
        return relationships.stream().filter(relationship -> relationship.ends().stream()
                .anyMatch(end -> end.id().equals(endId))).map(ModelResource.RelationshipResource::id)
                .findFirst().orElse(null);
    }

    private ConstraintOwner constraintOwner(SnapshotModel snapshot, UUID constraintId) {
        for (ModelResource.ClassifierResource classifier : snapshot.classifiers()) {
            if (classifier.constraints().stream().anyMatch(constraint -> constraint.id().equals(constraintId))) {
                return new ConstraintOwner(TargetType.CLASSIFIER, classifier.id());
            }
        }
        for (ModelResource.RelationshipResource relationship : snapshot.relationships()) {
            if (relationship.constraints().stream().anyMatch(constraint -> constraint.id().equals(constraintId))) {
                return new ConstraintOwner(TargetType.RELATIONSHIP, relationship.id());
            }
        }
        return new ConstraintOwner(null, null);
    }

    private UUID viewIdForGeometry(List<SnapshotModel.SnapshotView> views, UUID geometryId) {
        return views.stream().filter(view -> view.elementGeometry().stream()
                .anyMatch(geometry -> geometry.id().equals(geometryId))).map(SnapshotModel.SnapshotView::id)
                .findFirst().orElse(null);
    }

    private UUID viewIdForPath(List<SnapshotModel.SnapshotView> views, UUID pathId) {
        return views.stream().filter(view -> view.connectorPath().stream()
                .anyMatch(path -> path.id().equals(pathId))).map(SnapshotModel.SnapshotView::id)
                .findFirst().orElse(null);
    }

    private record ConstraintOwner(TargetType type, UUID id) {
    }

    private static <T> Comparator<T> byId(java.util.function.Function<T, UUID> id) {
        return Comparator.comparing(item -> id.apply(item).toString());
    }

    private static String line(String group, UUID id, String... fields) {
        return group + "|" + id + "|" + String.join("|", fields);
    }

    private static String field(String name, Object value) {
        return name + "=" + String.valueOf(value);
    }

    /** Appended only when set, so a model without a manual store keeps the hash it had before the field existed. */
    private static String withStore(String line, PersistenceStore storeOverride) {
        return storeOverride == null ? line : line + "|" + field("storeOverride", storeOverride);
    }

    /** Appended only when set, so a relationship without a stereotype keeps the hash it had before the field existed. */
    private static String withStereotype(String line, String stereotype) {
        return stereotype == null ? line : line + "|" + field("stereotype", stereotype);
    }

    private static UUID id(Object entity) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof Classifier classifier) {
            return classifier.getId();
        }
        if (entity instanceof com.diagramador.domain.TypeReference reference) {
            return reference.getId();
        }
        if (entity instanceof UmlOperation operation) {
            return operation.getId();
        }
        if (entity instanceof UmlRelationship relationship) {
            return relationship.getId();
        }
        if (entity instanceof com.diagramador.domain.DiagramView view) {
            return view.getId();
        }
        return null;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte valueByte : digest) {
                hex.append(String.format("%02x", valueByte));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
