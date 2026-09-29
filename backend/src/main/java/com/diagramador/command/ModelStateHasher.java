package com.diagramador.command;

import com.diagramador.domain.Classifier;
import com.diagramador.domain.ElementGeometry;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlConstraint;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UmlOperation;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.ElementGeometryRepository;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
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
    private final ElementGeometryRepository geometryRepository;

    public ModelStateHasher(ClassifierRepository classifierRepository, UmlAttributeRepository attributeRepository,
            UmlOperationRepository operationRepository, OperationParameterRepository parameterRepository,
            UmlRelationshipRepository relationshipRepository, RelationshipEndRepository relationshipEndRepository,
            UmlConstraintRepository constraintRepository, ElementGeometryRepository geometryRepository) {
        this.classifierRepository = classifierRepository;
        this.attributeRepository = attributeRepository;
        this.operationRepository = operationRepository;
        this.parameterRepository = parameterRepository;
        this.relationshipRepository = relationshipRepository;
        this.relationshipEndRepository = relationshipEndRepository;
        this.constraintRepository = constraintRepository;
        this.geometryRepository = geometryRepository;
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

        classifiers.stream().sorted(byId(Classifier::getId))
                .map(classifier -> line("classifiers", classifier.getId(),
                        field("namespaceId", id(classifier.getNamespace())), field("kind", classifier.getKind()),
                        field("name", classifier.getName()), field("isAbstract", classifier.isAbstract())))
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
                .map(relationship -> line("relationships", relationship.getId(),
                        field("kind", relationship.getKind()), field("name", relationship.getName())))
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
        geometryRepository.findByViewModelId(modelId).stream().sorted(byId(ElementGeometry::getId))
                .map(geometry -> line("geometries", geometry.getId(), field("viewId", id(geometry.getView())),
                        field("targetType", geometry.getTargetType()), field("targetId", geometry.getTargetId()),
                        field("x", geometry.getX()), field("y", geometry.getY()), field("w", geometry.getW()),
                        field("h", geometry.getH()), field("zOrder", geometry.getZOrder())))
                .forEach(lines::add);

        String canonicalProjection = String.join("\n", lines);
        return sha256(canonicalProjection);
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
