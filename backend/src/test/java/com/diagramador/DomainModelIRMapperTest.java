package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.command.ValidationState;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.generation.DomainModelIR;
import com.diagramador.generation.DomainModelIR.CollectionIntent;
import com.diagramador.generation.DomainModelIR.LifecycleIntent;
import com.diagramador.generation.DomainModelIR.RelationshipDescriptor;
import com.diagramador.generation.DomainModelIRMapper;
import com.diagramador.generation.GenerationDiagnostic;
import com.diagramador.generation.InputSnapshot;
import com.diagramador.generation.InputSnapshot.ClassifierElement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainModelIRMapperTest {

    private static final UUID ORDER_ID = id(1);
    private static final UUID CUSTOMER_ID = id(2);
    private static final UUID RELATIONSHIP_ID = id(10);
    private static final UUID MANY_END_ID = id(11);
    private static final UUID ONE_END_ID = id(12);
    private static final UUID TYPE_REFERENCE_ID = id(20);
    private static final UUID ATTRIBUTE_ID = id(30);
    private static final UUID OPERATION_ID = id(40);
    private static final UUID PARAMETER_ID = id(50);
    private static final String CANONICAL_PROJECTION = "unit-test-snapshot";

    private final DomainModelIRMapper mapper = new DomainModelIRMapper();

    @Test
    void invertingRelationshipEndOrderChangesNormalizedSequenceButKeepsOwningField() {
        InputSnapshot manyFirst = relationshipSnapshot(RelationshipKind.ASSOCIATION,
                MultiplicityKind.ONE_OR_MANY, MultiplicityKind.ZERO_OR_ONE,
                NavigationKind.NAVIGABLE, NavigationKind.NAVIGABLE, false);
        InputSnapshot oneFirst = relationshipSnapshot(RelationshipKind.ASSOCIATION,
                MultiplicityKind.ONE_OR_MANY, MultiplicityKind.ZERO_OR_ONE,
                NavigationKind.NAVIGABLE, NavigationKind.NAVIGABLE, true);
        List<InputSnapshot.RelationshipEndElement> normalizedManyFirst = normalizedEnds(manyFirst);
        List<InputSnapshot.RelationshipEndElement> normalizedOneFirst = normalizedEnds(oneFirst);

        assertThat(normalizedManyFirst).isNotEqualTo(normalizedOneFirst);
        assertThat(normalizedManyFirst.get(0).id()).isEqualTo(MANY_END_ID);
        assertThat(normalizedManyFirst.get(0).multiplicity()).isEqualTo(MultiplicityKind.ONE_OR_MANY);
        assertThat(normalizedOneFirst.get(0).id()).isEqualTo(MANY_END_ID);
        assertThat(normalizedOneFirst.get(0).multiplicity()).isEqualTo(MultiplicityKind.ZERO_OR_ONE);
        assertThat(normalizedOneFirst.get(1).id()).isEqualTo(ONE_END_ID);
        assertThat(normalizedOneFirst.get(1).multiplicity()).isEqualTo(MultiplicityKind.ONE_OR_MANY);
        assertThat(normalizedOneFirst.get(1).classifierId()).isEqualTo(ORDER_ID);
        assertThat(normalizedOneFirst.get(1).role()).isEqualTo("orders");

        DomainModelIR firstIr = mapper.map(manyFirst);
        DomainModelIR invertedIr = mapper.map(oneFirst);
        RelationshipDescriptor relation = firstIr.relationships().get(0);
        RelationshipDescriptor invertedRelation = invertedIr.relationships().get(0);

        assertThat(relation.owningEnd().sourceId()).isEqualTo(MANY_END_ID);
        assertThat(invertedRelation.owningEnd().sourceId()).isEqualTo(ONE_END_ID);
        assertThat(relation.owningEnd().classifierSourceId()).isEqualTo(ORDER_ID);
        assertThat(invertedRelation.owningEnd().classifierSourceId()).isEqualTo(ORDER_ID);
        assertThat(relation.referencedEnd().classifierSourceId()).isEqualTo(CUSTOMER_ID);
        assertThat(invertedRelation.referencedEnd().classifierSourceId()).isEqualTo(CUSTOMER_ID);
        assertThat(relation.owningFieldName()).isEqualTo("customer");
        assertThat(invertedRelation.owningFieldName()).isEqualTo("customer");
        assertThat(relation.inverseCollectionFieldName()).isEqualTo("orders");
        assertThat(invertedRelation.inverseCollectionFieldName()).isEqualTo("orders");
    }

    @Test
    void toManyEndSortingLastStillOwnsForeignKey() {
        InputSnapshot snapshot = relationshipSnapshotWithManyEndSortingLast();
        List<InputSnapshot.RelationshipEndElement> normalized = normalizedEnds(snapshot);

        assertThat(normalized.get(0).multiplicity()).isEqualTo(MultiplicityKind.ZERO_OR_ONE);
        assertThat(normalized.get(1).multiplicity()).isEqualTo(MultiplicityKind.ONE_OR_MANY);
        assertThat(normalized.get(1).classifierId()).isEqualTo(ORDER_ID);
        assertThat(normalized.get(1).role()).isEqualTo("orders");

        RelationshipDescriptor relation = mapper.map(snapshot).relationships().get(0);

        assertThat(relation.owningEnd().sourceId()).isEqualTo(ONE_END_ID);
        assertThat(relation.owningEnd().classifierSourceId()).isEqualTo(ORDER_ID);
        assertThat(relation.referencedEnd().sourceId()).isEqualTo(MANY_END_ID);
        assertThat(relation.referencedEnd().classifierSourceId()).isEqualTo(CUSTOMER_ID);
        assertThat(relation.owningFieldName()).isEqualTo("customer");
        assertThat(relation.inverseCollectionFieldName()).isEqualTo("orders");
    }

    @Test
    void toManyEndOwnsForeignKeyAndToOneEndReceivesInverseCollection() {
        DomainModelIR ir = mapper.map(relationshipSnapshot(RelationshipKind.ASSOCIATION,
                MultiplicityKind.ZERO_OR_MANY, MultiplicityKind.EXACTLY_ONE,
                NavigationKind.NAVIGABLE, NavigationKind.NAVIGABLE, false));

        RelationshipDescriptor relation = ir.relationships().get(0);
        assertThat(relation.owningEnd().sourceId()).isEqualTo(MANY_END_ID);
        assertThat(relation.owningEnd().classifierSourceId()).isEqualTo(ORDER_ID);
        assertThat(relation.referencedEnd().sourceId()).isEqualTo(ONE_END_ID);
        assertThat(relation.referencedEnd().classifierSourceId()).isEqualTo(CUSTOMER_ID);
        assertThat(relation.owningFieldName()).isEqualTo("customer");
        assertThat(relation.inverseCollectionFieldName()).isEqualTo("orders");
        assertThat(relation.inverseCollectionEmitted()).isTrue();
    }

    @Test
    void zeroOrOneToOneEndMakesOwningFieldNullable() {
        DomainModelIR ir = mapper.map(relationshipSnapshot(RelationshipKind.ASSOCIATION,
                MultiplicityKind.ONE_OR_MANY, MultiplicityKind.ZERO_OR_ONE,
                NavigationKind.NAVIGABLE, NavigationKind.NAVIGABLE, false));

        assertThat(ir.relationships().get(0).owningFieldNullable()).isTrue();
    }

    @Test
    void exactlyOneToOneEndMakesOwningFieldNonNullable() {
        DomainModelIR ir = mapper.map(relationshipSnapshot(RelationshipKind.ASSOCIATION,
                MultiplicityKind.ONE_OR_MANY, MultiplicityKind.EXACTLY_ONE,
                NavigationKind.NAVIGABLE, NavigationKind.NAVIGABLE, false));

        assertThat(ir.relationships().get(0).owningFieldNullable()).isFalse();
    }

    @Test
    void omitsInverseCollectionWhenToManyEndIsNotNavigableWithoutDiagnostic() {
        DomainModelIR ir = mapper.map(relationshipSnapshot(RelationshipKind.ASSOCIATION,
                MultiplicityKind.ONE_OR_MANY, MultiplicityKind.EXACTLY_ONE,
                NavigationKind.NOT_NAVIGABLE, NavigationKind.NAVIGABLE, false));

        RelationshipDescriptor relation = ir.relationships().get(0);
        assertThat(relation.inverseCollectionEmitted()).isFalse();
        assertThat(relation.inverseCollectionFieldName()).isNull();
        assertThat(ir.diagnostics()).isEmpty();
    }

    @Test
    void blocksNonNavigableToOneEndAndNamesThatEnd() {
        DomainModelIR ir = mapper.map(relationshipSnapshot(RelationshipKind.ASSOCIATION,
                MultiplicityKind.ONE_OR_MANY, MultiplicityKind.EXACTLY_ONE,
                NavigationKind.NAVIGABLE, NavigationKind.NOT_NAVIGABLE, false));

        GenerationDiagnostic diagnostic = ir.diagnostics().get(0);
        assertThat(diagnostic.elementId()).isEqualTo(ONE_END_ID);
        assertThat(diagnostic.feature()).isEqualTo("navigability");
        assertThat(diagnostic.message()).contains("customer", ONE_END_ID.toString());
        assertThat(diagnostic.blocking()).isTrue();
    }

    @Test
    void blocksGeneralizationByFeatureName() {
        assertUnsupportedRelationshipNames(RelationshipKind.GENERALIZATION);
    }

    @Test
    void blocksInterfaceRealizationByFeatureName() {
        assertUnsupportedRelationshipNames(RelationshipKind.INTERFACE_REALIZATION);
    }

    @Test
    void blocksDependencyByFeatureName() {
        assertUnsupportedRelationshipNames(RelationshipKind.DEPENDENCY);
    }

    @Test
    void blocksOneToOneWithMultiplicityCombination() {
        DomainModelIR ir = mapper.map(relationshipSnapshot(RelationshipKind.ASSOCIATION,
                MultiplicityKind.EXACTLY_ONE, MultiplicityKind.ZERO_OR_ONE,
                NavigationKind.NAVIGABLE, NavigationKind.NAVIGABLE, false));

        assertThat(ir.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("UNSUPPORTED_MULTIPLICITY_COMBINATION");
            assertThat(diagnostic.message()).contains("EXACTLY_ONE + ZERO_OR_ONE");
            assertThat(diagnostic.blocking()).isTrue();
        });
    }

    @Test
    void blocksManyToManyWithMultiplicityCombination() {
        DomainModelIR ir = mapper.map(relationshipSnapshot(RelationshipKind.ASSOCIATION,
                MultiplicityKind.ONE_OR_MANY, MultiplicityKind.ZERO_OR_MANY,
                NavigationKind.NAVIGABLE, NavigationKind.NAVIGABLE, false));

        assertThat(ir.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("UNSUPPORTED_MULTIPLICITY_COMBINATION");
            assertThat(diagnostic.message()).contains("ONE_OR_MANY + ZERO_OR_MANY");
            assertThat(diagnostic.blocking()).isTrue();
        });
    }

    @Test
    void unresolvedAttributeTypeProducesBlockingDiagnostic() {
        InputSnapshot snapshot = snapshot(
                List.of(classifier(ORDER_ID, ClassifierKind.CLASS, "Order")),
                List.of(attribute(ATTRIBUTE_ID, ORDER_ID, id(999), "code", 0)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        DomainModelIR ir = mapper.map(snapshot);

        assertThat(ir.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("UNRESOLVABLE_TYPE");
            assertThat(diagnostic.elementId()).isEqualTo(ATTRIBUTE_ID);
            assertThat(diagnostic.blocking()).isTrue();
        });
        assertThat(ir.types().get(0).fields().get(0).type().resolved()).isFalse();
    }

    @Test
    void resolvesAttributeParameterAndReturnTypesAgainstSnapshotReferences() {
        InputSnapshot snapshot = snapshot(
                List.of(classifier(ORDER_ID, ClassifierKind.CLASS, "Order")),
                List.of(attribute(ATTRIBUTE_ID, ORDER_ID, TYPE_REFERENCE_ID, "code", 0)),
                List.of(new InputSnapshot.OperationElement(OPERATION_ID, ORDER_ID, "lookup", TYPE_REFERENCE_ID,
                        "PUBLIC", false, false, 0)),
                List.of(new InputSnapshot.ParameterElement(PARAMETER_ID, OPERATION_ID, TYPE_REFERENCE_ID,
                        "candidate", "IN", 0)),
                List.of(new InputSnapshot.TypeReferenceElement(TYPE_REFERENCE_ID, null, "String")),
                List.of(), List.of(), List.of());

        DomainModelIR ir = mapper.map(snapshot);
        DomainModelIR.TypeDeclaration type = ir.types().get(0);

        assertThat(ir.diagnostics()).isEmpty();
        assertThat(type.fields().get(0).type().javaTypeName()).isEqualTo("String");
        assertThat(type.operations().get(0).returnType().javaTypeName()).isEqualTo("String");
        assertThat(type.operations().get(0).parameters().get(0).type().javaTypeName()).isEqualTo("String");
    }

    @Test
    void accumulatesTwoUnsupportedFeaturesInOnePass() {
        List<ClassifierElement> classifiers = new ArrayList<>(List.of(
                classifier(ORDER_ID, ClassifierKind.CLASS, "Order"),
                classifier(id(3), ClassifierKind.INTERFACE, "Payable")));
        InputSnapshot snapshot = snapshot(classifiers, List.of(), List.of(), List.of(), List.of(),
                List.of(new InputSnapshot.RelationshipElement(RELATIONSHIP_ID, RelationshipKind.DEPENDENCY,
                        "uses")), List.of(), List.of());

        DomainModelIR ir = mapper.map(snapshot);

        assertThat(ir.diagnostics()).hasSize(2);
        assertThat(ir.diagnostics()).extracting(GenerationDiagnostic::feature)
                .containsExactlyInAnyOrder("INTERFACE", "DEPENDENCY");
        assertThat(ir.diagnostics()).allSatisfy(diagnostic -> assertThat(diagnostic.blocking()).isTrue());
    }

    @Test
    void mappingTheSameSnapshotTwiceProducesEqualIr() {
        InputSnapshot snapshot = relationshipSnapshot(RelationshipKind.COMPOSITION,
                MultiplicityKind.ONE_OR_MANY, MultiplicityKind.ZERO_OR_ONE,
                NavigationKind.NAVIGABLE, NavigationKind.NAVIGABLE, false);

        assertThat(mapper.map(snapshot)).isEqualTo(mapper.map(snapshot));
    }

    @Test
    void preservesAggregationKindAndLifecycleIntentOnRelationshipDescriptor() {
        InputSnapshot snapshot = relationshipSnapshot(RelationshipKind.COMPOSITION,
                MultiplicityKind.ONE_OR_MANY, MultiplicityKind.ZERO_OR_ONE,
                NavigationKind.NAVIGABLE, NavigationKind.NAVIGABLE, false,
                "NONE", "COMPOSITE");

        RelationshipDescriptor relation = mapper.map(snapshot).relationships().get(0);

        assertThat(relation.referencedEnd().aggregationKind()).isEqualTo("COMPOSITE");
        assertThat(relation.referencedEnd().lifecycleIntent()).isEqualTo(LifecycleIntent.COMPOSITE);
        assertThat(relation.lifecycleIntent()).isEqualTo(LifecycleIntent.COMPOSITE);
    }

    @Test
    void mapsAttributeMultiplicityToCollectionAndNullabilityIntent() {
        InputSnapshot snapshot = snapshot(
                List.of(classifier(ORDER_ID, ClassifierKind.CLASS, "Order")),
                List.of(new InputSnapshot.AttributeElement(ATTRIBUTE_ID, ORDER_ID, TYPE_REFERENCE_ID, "codes",
                        "ATTRIBUTE", "PRIVATE", MultiplicityKind.ZERO_OR_MANY, null, 0)),
                List.of(), List.of(),
                List.of(new InputSnapshot.TypeReferenceElement(TYPE_REFERENCE_ID, null, "String")),
                List.of(), List.of(), List.of());

        DomainModelIR.FieldDeclaration field = mapper.map(snapshot).types().get(0).fields().get(0);

        assertThat(field.collectionIntent()).isEqualTo(CollectionIntent.COLLECTION);
        assertThat(field.nullable()).isTrue();
        assertThat(field.bounds().lowerBound()).isZero();
        assertThat(field.bounds().upperBound()).isNull();
    }

    private void assertUnsupportedRelationshipNames(RelationshipKind kind) {
        DomainModelIR ir = mapper.map(relationshipSnapshot(kind,
                MultiplicityKind.ONE_OR_MANY, MultiplicityKind.EXACTLY_ONE,
                NavigationKind.NAVIGABLE, NavigationKind.NAVIGABLE, false));

        assertThat(ir.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("UNSUPPORTED_UML_FEATURE");
            assertThat(diagnostic.feature()).isEqualTo(kind.name());
            assertThat(diagnostic.message()).contains(kind.name());
            assertThat(diagnostic.blocking()).isTrue();
        });
    }

    private static InputSnapshot relationshipSnapshot(
            RelationshipKind kind,
            MultiplicityKind manyMultiplicity,
            MultiplicityKind oneMultiplicity,
            NavigationKind manyNavigability,
            NavigationKind oneNavigability,
            boolean invertEnds) {
        return relationshipSnapshot(kind, manyMultiplicity, oneMultiplicity, manyNavigability, oneNavigability,
                invertEnds, "NONE", "NONE");
    }

    private static InputSnapshot relationshipSnapshot(
            RelationshipKind kind,
            MultiplicityKind manyMultiplicity,
            MultiplicityKind oneMultiplicity,
            NavigationKind manyNavigability,
            NavigationKind oneNavigability,
            boolean invertEnds,
            String manyAggregation,
            String oneAggregation) {
        UUID manyEndId = invertEnds ? ONE_END_ID : MANY_END_ID;
        UUID oneEndId = invertEnds ? MANY_END_ID : ONE_END_ID;
        InputSnapshot.RelationshipEndElement manyEnd = new InputSnapshot.RelationshipEndElement(manyEndId,
                RELATIONSHIP_ID, ORDER_ID, (short) 1, "orders", manyMultiplicity, manyNavigability, manyAggregation);
        InputSnapshot.RelationshipEndElement oneEnd = new InputSnapshot.RelationshipEndElement(oneEndId,
                RELATIONSHIP_ID, CUSTOMER_ID, (short) 2, "customer", oneMultiplicity, oneNavigability, oneAggregation);
        List<InputSnapshot.RelationshipEndElement> ends = invertEnds
                ? List.of(oneEnd, manyEnd)
                : List.of(manyEnd, oneEnd);
        return snapshot(List.of(classifier(ORDER_ID, ClassifierKind.CLASS, "Order"),
                        classifier(CUSTOMER_ID, ClassifierKind.CLASS, "Customer")),
                List.of(), List.of(), List.of(), List.of(),
                List.of(new InputSnapshot.RelationshipElement(RELATIONSHIP_ID, kind, "orderCustomer")), ends,
                List.of());
    }

    private static List<InputSnapshot.RelationshipEndElement> normalizedEnds(InputSnapshot snapshot) {
        return snapshot.relationshipEnds().stream()
                .sorted(Comparator.comparing(end -> end.id().toString()))
                .toList();
    }

    private static InputSnapshot relationshipSnapshotWithManyEndSortingLast() {
        InputSnapshot.RelationshipEndElement manyEnd = new InputSnapshot.RelationshipEndElement(ONE_END_ID,
                RELATIONSHIP_ID, ORDER_ID, (short) 1, "orders", MultiplicityKind.ONE_OR_MANY,
                NavigationKind.NAVIGABLE, "NONE");
        InputSnapshot.RelationshipEndElement oneEnd = new InputSnapshot.RelationshipEndElement(MANY_END_ID,
                RELATIONSHIP_ID, CUSTOMER_ID, (short) 2, "customer", MultiplicityKind.ZERO_OR_ONE,
                NavigationKind.NAVIGABLE, "NONE");
        return snapshot(List.of(classifier(ORDER_ID, ClassifierKind.CLASS, "Order"),
                        classifier(CUSTOMER_ID, ClassifierKind.CLASS, "Customer")),
                List.of(), List.of(), List.of(), List.of(),
                List.of(new InputSnapshot.RelationshipElement(RELATIONSHIP_ID, RelationshipKind.ASSOCIATION,
                        "orderCustomer")),
                List.of(manyEnd, oneEnd), List.of());
    }

    private static InputSnapshot.AttributeElement attribute(UUID id, UUID classifierId, UUID typeReferenceId,
            String name, int ordinal) {
        return new InputSnapshot.AttributeElement(id, classifierId, typeReferenceId, name, "ATTRIBUTE", "PUBLIC",
                MultiplicityKind.EXACTLY_ONE, null, ordinal);
    }

    private static InputSnapshot.ClassifierElement classifier(UUID id, ClassifierKind kind, String name) {
        return new InputSnapshot.ClassifierElement(id, null, kind, name, kind == ClassifierKind.ABSTRACT_CLASS);
    }

    private static InputSnapshot snapshot(
            List<InputSnapshot.ClassifierElement> classifiers,
            List<InputSnapshot.AttributeElement> attributes,
            List<InputSnapshot.OperationElement> operations,
            List<InputSnapshot.ParameterElement> parameters,
            List<InputSnapshot.TypeReferenceElement> typeReferences,
            List<InputSnapshot.RelationshipElement> relationships,
            List<InputSnapshot.RelationshipEndElement> relationshipEnds,
            List<InputSnapshot.ConstraintElement> constraints) {
        return new InputSnapshot("USER_DOMAIN_CLASS_DIAGRAM", id(1000), id(1001), 1L, "model-state-hash",
                ValidationState.VALID, sha256(CANONICAL_PROJECTION), classifiers, attributes, operations, parameters,
                typeReferences, relationships, relationshipEnds, constraints, CANONICAL_PROJECTION);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
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
