package com.diagramador.generation;

import com.diagramador.command.ValidationState;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ModelVersion;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.PersistenceStore;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlConstraint;
import com.diagramador.domain.UmlOperation;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.TypeReferenceRepository;
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
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Builds immutable, semantically scoped generation input from a validated model version. */
@Component
public class InputSnapshotFactory {

    public static final String SOURCE = "USER_DOMAIN_CLASS_DIAGRAM";

    private final ClassifierRepository classifierRepository;
    private final UmlAttributeRepository attributeRepository;
    private final UmlOperationRepository operationRepository;
    private final OperationParameterRepository parameterRepository;
    private final TypeReferenceRepository typeReferenceRepository;
    private final UmlRelationshipRepository relationshipRepository;
    private final RelationshipEndRepository relationshipEndRepository;
    private final UmlConstraintRepository constraintRepository;

    public InputSnapshotFactory(ClassifierRepository classifierRepository,
            UmlAttributeRepository attributeRepository,
            UmlOperationRepository operationRepository,
            OperationParameterRepository parameterRepository,
            TypeReferenceRepository typeReferenceRepository,
            UmlRelationshipRepository relationshipRepository,
            RelationshipEndRepository relationshipEndRepository,
            UmlConstraintRepository constraintRepository) {
        this.classifierRepository = classifierRepository;
        this.attributeRepository = attributeRepository;
        this.operationRepository = operationRepository;
        this.parameterRepository = parameterRepository;
        this.typeReferenceRepository = typeReferenceRepository;
        this.relationshipRepository = relationshipRepository;
        this.relationshipEndRepository = relationshipEndRepository;
        this.constraintRepository = constraintRepository;
    }

    @Transactional(readOnly = true)
    public InputSnapshot create(ModelVersion version) {
        ValidationState validationState = version.getValidationState();
        if (validationState != ValidationState.VALID) {
            throw new IllegalArgumentException(
                    "Cannot create input snapshot from model version in validation state " + validationState);
        }

        UUID modelId = version.getModel().getId();
        List<Classifier> classifierRows = classifierRepository.findByModelId(modelId).stream()
                .sorted(byId(Classifier::getId)).toList();
        List<UmlAttribute> attributeRows = new ArrayList<>();
        List<UmlOperation> operationRows = new ArrayList<>();
        List<OperationParameter> parameterRows = new ArrayList<>();
        for (Classifier classifier : classifierRows) {
            attributeRows.addAll(attributeRepository.findByClassifierId(classifier.getId()));
            List<UmlOperation> classifierOperations = operationRepository.findByClassifierId(classifier.getId());
            operationRows.addAll(classifierOperations);
            for (UmlOperation operation : classifierOperations) {
                parameterRows.addAll(parameterRepository.findByOperationId(operation.getId()));
            }
        }

        List<TypeReference> typeReferenceRows = typeReferenceRepository.findByModelId(modelId);
        List<UmlRelationship> relationshipRows = relationshipRepository.findByModelId(modelId);
        List<RelationshipEnd> relationshipEndRows = new ArrayList<>();
        for (UmlRelationship relationship : relationshipRows) {
            relationshipEndRows.addAll(relationshipEndRepository.findByRelationshipId(relationship.getId()));
        }
        List<UmlConstraint> constraintRows = constraintRepository.findByModelId(modelId);

        List<InputSnapshot.ClassifierElement> classifiers = classifierRows.stream()
                .map(classifier -> new InputSnapshot.ClassifierElement(classifier.getId(),
                        id(classifier.getNamespace()), classifier.getKind(), classifier.getName(),
                        classifier.isAbstract(), classifier.getStoreOverride()))
                .toList();
        List<InputSnapshot.AttributeElement> attributes = attributeRows.stream()
                .sorted(byId(UmlAttribute::getId))
                .map(attribute -> new InputSnapshot.AttributeElement(attribute.getId(),
                        attribute.getClassifier().getId(), id(attribute.getTypeReference()), attribute.getName(),
                        attribute.getKind(), attribute.getVisibility(), attribute.getMultiplicity(),
                        attribute.getDefaultValue(), attribute.getOrdinal()))
                .toList();
        List<InputSnapshot.OperationElement> operations = operationRows.stream()
                .sorted(byId(UmlOperation::getId))
                .map(operation -> new InputSnapshot.OperationElement(operation.getId(),
                        operation.getClassifier().getId(), operation.getName(), id(operation.getReturnTypeReference()),
                        operation.getVisibility(), operation.isAbstract(), operation.isStatic(), operation.getOrdinal()))
                .toList();
        List<InputSnapshot.ParameterElement> parameters = parameterRows.stream()
                .sorted(byId(OperationParameter::getId))
                .map(parameter -> new InputSnapshot.ParameterElement(parameter.getId(),
                        parameter.getOperation().getId(), id(parameter.getTypeReference()), parameter.getName(),
                        parameter.getDirection(), parameter.getOrdinal()))
                .toList();
        List<InputSnapshot.TypeReferenceElement> typeReferences = typeReferenceRows.stream()
                .sorted(byId(TypeReference::getId))
                .map(reference -> new InputSnapshot.TypeReferenceElement(reference.getId(),
                        id(reference.getClassifier()), reference.getPrimitiveName()))
                .toList();
        List<InputSnapshot.RelationshipElement> relationships = relationshipRows.stream()
                .sorted(byId(UmlRelationship::getId))
                .map(relationship -> new InputSnapshot.RelationshipElement(relationship.getId(),
                        relationship.getKind(), relationship.getName()))
                .toList();
        List<InputSnapshot.RelationshipEndElement> relationshipEnds = relationshipEndRows.stream()
                .sorted(byId(RelationshipEnd::getId))
                .map(end -> new InputSnapshot.RelationshipEndElement(end.getId(), end.getRelationship().getId(),
                        end.getClassifier().getId(), end.getOrdinal(), end.getRole(), end.getMultiplicity(),
                        end.getNavigability(), end.getAggregationKind()))
                .toList();
        List<InputSnapshot.ConstraintElement> constraints = constraintRows.stream()
                .sorted(byId(UmlConstraint::getId))
                .map(constraint -> new InputSnapshot.ConstraintElement(constraint.getId(), constraint.getOwnerType(),
                        constraint.getOwnerId(), constraint.getExpression(), constraint.getSeverity()))
                .toList();

        String canonicalProjection = canonicalProjection(classifiers, attributes, operations, parameters,
                typeReferences, relationships, relationshipEnds, constraints);
        return new InputSnapshot(SOURCE, modelId, version.getId(), version.getRevision(), version.getStateHash(),
                validationState, sha256(canonicalProjection), classifiers, attributes, operations, parameters,
                typeReferences, relationships, relationshipEnds, constraints, canonicalProjection);
    }

    private static String canonicalProjection(
            List<InputSnapshot.ClassifierElement> classifiers,
            List<InputSnapshot.AttributeElement> attributes,
            List<InputSnapshot.OperationElement> operations,
            List<InputSnapshot.ParameterElement> parameters,
            List<InputSnapshot.TypeReferenceElement> typeReferences,
            List<InputSnapshot.RelationshipElement> relationships,
            List<InputSnapshot.RelationshipEndElement> relationshipEnds,
            List<InputSnapshot.ConstraintElement> constraints) {
        List<String> lines = new ArrayList<>();
        classifiers.stream().sorted(byId(InputSnapshot.ClassifierElement::id))
                .map(classifier -> withStore(line("classifiers", classifier.id(),
                        field("namespaceId", classifier.namespaceId()), field("kind", classifier.kind()),
                        field("name", classifier.name()), field("isAbstract", classifier.isAbstract())),
                        classifier.storeOverride()))
                .forEach(lines::add);
        attributes.stream().sorted(byId(InputSnapshot.AttributeElement::id))
                .map(attribute -> line("attributes", attribute.id(),
                        field("classifierId", attribute.classifierId()),
                        field("typeReferenceId", attribute.typeReferenceId()), field("name", attribute.name()),
                        field("kind", attribute.kind()), field("visibility", attribute.visibility()),
                        field("multiplicity", attribute.multiplicity()), field("defaultValue", attribute.defaultValue()),
                        field("ordinal", attribute.ordinal())))
                .forEach(lines::add);
        operations.stream().sorted(byId(InputSnapshot.OperationElement::id))
                .map(operation -> line("operations", operation.id(),
                        field("classifierId", operation.classifierId()), field("name", operation.name()),
                        field("returnTypeReferenceId", operation.returnTypeReferenceId()),
                        field("visibility", operation.visibility()), field("isAbstract", operation.isAbstract()),
                        field("isStatic", operation.isStatic()), field("ordinal", operation.ordinal())))
                .forEach(lines::add);
        parameters.stream().sorted(byId(InputSnapshot.ParameterElement::id))
                .map(parameter -> line("parameters", parameter.id(),
                        field("operationId", parameter.operationId()),
                        field("typeReferenceId", parameter.typeReferenceId()), field("name", parameter.name()),
                        field("direction", parameter.direction()), field("ordinal", parameter.ordinal())))
                .forEach(lines::add);
        typeReferences.stream().sorted(byId(InputSnapshot.TypeReferenceElement::id))
                .map(reference -> line("typeReferences", reference.id(),
                        field("classifierId", reference.classifierId()), field("primitiveName", reference.primitiveName())))
                .forEach(lines::add);
        relationships.stream().sorted(byId(InputSnapshot.RelationshipElement::id))
                .map(relationship -> line("relationships", relationship.id(),
                        field("kind", relationship.kind()), field("name", relationship.name())))
                .forEach(lines::add);
        relationshipEnds.stream().sorted(byId(InputSnapshot.RelationshipEndElement::id))
                .map(end -> line("relationshipEnds", end.id(), field("relationshipId", end.relationshipId()),
                        field("classifierId", end.classifierId()), field("ordinal", end.ordinal()),
                        field("role", end.role()), field("multiplicity", end.multiplicity()),
                        field("navigability", end.navigability()), field("aggregationKind", end.aggregationKind())))
                .forEach(lines::add);
        constraints.stream().sorted(byId(InputSnapshot.ConstraintElement::id))
                .map(constraint -> line("constraints", constraint.id(), field("ownerType", constraint.ownerType()),
                        field("ownerId", constraint.ownerId()), field("expression", constraint.expression()),
                        field("severity", constraint.severity())))
                .forEach(lines::add);

        // Layout-only groups are intentionally excluded: diagram views, element geometries, and connector paths.
        return String.join("\n", lines);
    }

    private static <T> Comparator<T> byId(Function<T, UUID> id) {
        return Comparator.comparing(item -> id.apply(item).toString());
    }

    private static String line(String group, UUID id, String... fields) {
        return group + "|" + id + "|" + String.join("|", fields);
    }

    private static String field(String name, Object value) {
        return name + "=" + String.valueOf(value);
    }

    /** Appended only when set, so a model without a manual store keeps the projection it had before the field. */
    private static String withStore(String line, PersistenceStore storeOverride) {
        return storeOverride == null ? line : line + "|" + field("storeOverride", storeOverride);
    }

    private static UUID id(Classifier classifier) {
        return classifier == null ? null : classifier.getId();
    }

    private static UUID id(TypeReference reference) {
        return reference == null ? null : reference.getId();
    }

    static String sha256(String value) {
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
