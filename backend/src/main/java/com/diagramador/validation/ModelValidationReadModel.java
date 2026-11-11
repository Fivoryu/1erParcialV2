package com.diagramador.validation;

import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlOperation;
import com.diagramador.domain.UmlRelationship;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Immutable read-model of the canonical UML model, scoped to semantic validation needs. Built from
 * domain entities by the caller; the model itself is pure and holds no repository access.
 */
public record ModelValidationReadModel(
        List<ClassifierElement> classifiers,
        List<RelationshipElement> relationships,
        List<RelationshipEndElement> relationshipEnds,
        List<AttributeElement> attributes,
        List<OperationElement> operations,
        List<ParameterElement> parameters,
        List<TypeReferenceElement> typeReferences) {

    public ModelValidationReadModel {
        classifiers = List.copyOf(classifiers);
        relationships = List.copyOf(relationships);
        relationshipEnds = List.copyOf(relationshipEnds);
        attributes = List.copyOf(attributes);
        operations = List.copyOf(operations);
        parameters = List.copyOf(parameters);
        typeReferences = List.copyOf(typeReferences);
    }

    /** Projects loaded domain entity rows into the validation read-model without operation parameters. */
    public static ModelValidationReadModel from(List<Classifier> classifierRows,
            List<UmlRelationship> relationshipRows, List<RelationshipEnd> relationshipEndRows,
            List<UmlAttribute> attributeRows, List<UmlOperation> operationRows,
            List<TypeReference> typeReferenceRows) {
        return from(classifierRows, relationshipRows, relationshipEndRows, attributeRows, operationRows, List.of(),
                typeReferenceRows);
    }

    /** Projects loaded domain entity rows into the validation read-model, deterministically ordered by id. */
    public static ModelValidationReadModel from(List<Classifier> classifierRows,
            List<UmlRelationship> relationshipRows, List<RelationshipEnd> relationshipEndRows,
            List<UmlAttribute> attributeRows, List<UmlOperation> operationRows,
            List<OperationParameter> parameterRows, List<TypeReference> typeReferenceRows) {
        List<ClassifierElement> classifiers = classifierRows.stream()
                .sorted(byId(Classifier::getId))
                .map(classifier -> new ClassifierElement(classifier.getId(), id(classifier.getNamespace()),
                        classifier.getKind(), classifier.getName()))
                .toList();
        List<RelationshipElement> relationships = relationshipRows.stream()
                .sorted(byId(UmlRelationship::getId))
                .map(relationship -> new RelationshipElement(relationship.getId(), relationship.getKind(),
                        relationship.getName()))
                .toList();
        List<RelationshipEndElement> relationshipEnds = relationshipEndRows.stream()
                .sorted(byId(RelationshipEnd::getId))
                .map(end -> new RelationshipEndElement(end.getId(), id(end.getRelationship()), id(end.getClassifier()),
                        end.getOrdinal(), end.getRole(), end.getMultiplicity(), end.getLowerBound(),
                        end.getUpperBound()))
                .toList();
        List<AttributeElement> attributes = attributeRows.stream()
                .sorted(byId(UmlAttribute::getId))
                .map(attribute -> new AttributeElement(attribute.getId(), id(attribute.getClassifier()),
                        id(attribute.getTypeReference()), attribute.getName(), attribute.getMultiplicity(),
                        attribute.getOrdinal(), attribute.getLowerBound(), attribute.getUpperBound()))
                .toList();
        List<OperationElement> operations = operationRows.stream()
                .sorted(byId(UmlOperation::getId))
                .map(operation -> new OperationElement(operation.getId(), id(operation.getClassifier()),
                        id(operation.getReturnTypeReference()), operation.getName(), operation.getOrdinal()))
                .toList();
        List<ParameterElement> parameters = parameterRows.stream()
                .sorted(byId(OperationParameter::getId))
                .map(parameter -> new ParameterElement(parameter.getId(), id(parameter.getOperation()),
                        id(parameter.getTypeReference()), parameter.getName(), parameter.getOrdinal()))
                .toList();
        List<TypeReferenceElement> typeReferences = typeReferenceRows.stream()
                .sorted(byId(TypeReference::getId))
                .map(reference -> new TypeReferenceElement(reference.getId(), id(reference.getClassifier()),
                        reference.getPrimitiveName()))
                .toList();
        return new ModelValidationReadModel(classifiers, relationships, relationshipEnds, attributes, operations,
                parameters, typeReferences);
    }

    public record ClassifierElement(UUID id, UUID namespaceId, ClassifierKind kind, String name) {
    }

    public record RelationshipElement(UUID id, RelationshipKind kind, String name) {
    }

    public record RelationshipEndElement(
            UUID id,
            UUID relationshipId,
            UUID classifierId,
            short ordinal,
            String role,
            MultiplicityKind multiplicity,
            Integer lowerBound,
            Integer upperBound) {
        public RelationshipEndElement(UUID id, UUID relationshipId, UUID classifierId, short ordinal, String role,
                MultiplicityKind multiplicity) {
            this(id, relationshipId, classifierId, ordinal, role, multiplicity, null, null);
        }
    }

    public record AttributeElement(
            UUID id,
            UUID classifierId,
            UUID typeReferenceId,
            String name,
            MultiplicityKind multiplicity,
            int ordinal,
            Integer lowerBound,
            Integer upperBound) {
        public AttributeElement(UUID id, UUID classifierId, UUID typeReferenceId, String name,
                MultiplicityKind multiplicity, int ordinal) {
            this(id, classifierId, typeReferenceId, name, multiplicity, ordinal, null, null);
        }
    }

    public record OperationElement(
            UUID id,
            UUID classifierId,
            UUID returnTypeReferenceId,
            String name,
            int ordinal) {
    }

    public record ParameterElement(
            UUID id,
            UUID operationId,
            UUID typeReferenceId,
            String name,
            int ordinal) {
    }

    public record TypeReferenceElement(UUID id, UUID classifierId, String primitiveName) {
    }

    private static <T> Comparator<T> byId(Function<T, UUID> id) {
        return Comparator.comparing(item -> id.apply(item).toString());
    }

    private static UUID id(Classifier classifier) {
        return classifier == null ? null : classifier.getId();
    }

    private static UUID id(TypeReference reference) {
        return reference == null ? null : reference.getId();
    }

    private static UUID id(UmlRelationship relationship) {
        return relationship == null ? null : relationship.getId();
    }

    private static UUID id(UmlOperation operation) {
        return operation == null ? null : operation.getId();
    }

    private static UUID id(OperationParameter parameter) {
        return parameter == null ? null : parameter.getId();
    }

    private static UUID id(RelationshipEnd end) {
        return end == null ? null : end.getId();
    }
}
