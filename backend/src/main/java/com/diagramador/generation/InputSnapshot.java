package com.diagramador.generation;

import com.diagramador.command.ValidationState;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.SeverityKind;
import com.diagramador.domain.PersistenceStore;
import com.diagramador.domain.TargetType;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable semantic input and auditable canonical projection for one generation snapshot. */
public record InputSnapshot(
        String source,
        UUID modelId,
        UUID versionId,
        long revision,
        String modelStateHash,
        ValidationState validationState,
        String snapshotHash,
        List<ClassifierElement> classifiers,
        List<AttributeElement> attributes,
        List<OperationElement> operations,
        List<ParameterElement> parameters,
        List<TypeReferenceElement> typeReferences,
        List<RelationshipElement> relationships,
        List<RelationshipEndElement> relationshipEnds,
        List<ConstraintElement> constraints,
        String canonicalProjection) {

    public InputSnapshot {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(modelStateHash, "modelStateHash");
        Objects.requireNonNull(validationState, "validationState");
        if (validationState != ValidationState.VALID) {
            throw new IllegalArgumentException(
                    "Cannot create input snapshot from model version in validation state " + validationState);
        }
        Objects.requireNonNull(snapshotHash, "snapshotHash");
        classifiers = List.copyOf(classifiers);
        attributes = List.copyOf(attributes);
        operations = List.copyOf(operations);
        parameters = List.copyOf(parameters);
        typeReferences = List.copyOf(typeReferences);
        relationships = List.copyOf(relationships);
        relationshipEnds = List.copyOf(relationshipEnds);
        constraints = List.copyOf(constraints);
        Objects.requireNonNull(canonicalProjection, "canonicalProjection");
        if (!snapshotHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("snapshotHash must be 64 lowercase hexadecimal characters");
        }
        if (!snapshotHash.equals(InputSnapshotFactory.sha256(canonicalProjection))) {
            throw new IllegalArgumentException("snapshotHash does not match the canonical projection");
        }
    }

    public record ClassifierElement(
            UUID id,
            UUID namespaceId,
            ClassifierKind kind,
            String name,
            boolean isAbstract,
            PersistenceStore storeOverride) {

        public ClassifierElement(UUID id, UUID namespaceId, ClassifierKind kind, String name, boolean isAbstract) {
            this(id, namespaceId, kind, name, isAbstract, null);
        }
    }

    public record AttributeElement(
            UUID id,
            UUID classifierId,
            UUID typeReferenceId,
            String name,
            String kind,
            String visibility,
            MultiplicityKind multiplicity,
            String defaultValue,
            int ordinal) {
    }

    public record OperationElement(
            UUID id,
            UUID classifierId,
            String name,
            UUID returnTypeReferenceId,
            String visibility,
            boolean isAbstract,
            boolean isStatic,
            int ordinal) {
    }

    public record ParameterElement(
            UUID id,
            UUID operationId,
            UUID typeReferenceId,
            String name,
            String direction,
            int ordinal) {
    }

    public record TypeReferenceElement(UUID id, UUID classifierId, String primitiveName) {
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
            NavigationKind navigability,
            String aggregationKind) {
    }

    public record ConstraintElement(
            UUID id,
            TargetType ownerType,
            UUID ownerId,
            String expression,
            SeverityKind severity) {
    }
}
