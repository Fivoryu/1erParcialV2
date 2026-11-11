package com.diagramador.vcs;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.api.ModelResource.RelationshipResource;
import com.diagramador.domain.ClassifierKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VcsMergeDiffTest {

    private static final UUID CLASSIFIER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID FIRST_RELATIONSHIP_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_RELATIONSHIP_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ASSOCIATION_CLASS_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_ASSOCIATION_CLASS_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");

    @Test
    void divergentChangesToTheSameFieldAreReportedAsAFieldConflict() {
        SnapshotModel base = model(classifier(CLASSIFIER_ID, "Order", ClassifierKind.CLASS));
        SnapshotModel source = model(classifier(CLASSIFIER_ID, "SaleOrder", ClassifierKind.CLASS));
        SnapshotModel target = model(classifier(CLASSIFIER_ID, "PurchaseOrder", ClassifierKind.CLASS));

        MergeDiff.Result result = MergeDiff.merge(base, source, target);

        assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.kind()).isEqualTo("FIELD_CONFLICT");
            assertThat(conflict.targetType()).isEqualTo("CLASSIFIER");
            assertThat(conflict.targetId()).isEqualTo(CLASSIFIER_ID);
            assertThat(conflict.field()).isEqualTo("name");
            assertThat(conflict.baseValue()).isEqualTo("Order");
            assertThat(conflict.sourceValue()).isEqualTo("SaleOrder");
            assertThat(conflict.targetValue()).isEqualTo("PurchaseOrder");
        });
    }

    @Test
    void deletingAnElementWhileTheOtherSideModifiesItIsDeleteModify() {
        SnapshotModel base = model(classifier(CLASSIFIER_ID, "Order", ClassifierKind.CLASS));
        SnapshotModel source = model();
        SnapshotModel target = model(classifier(CLASSIFIER_ID, "RenamedOrder", ClassifierKind.CLASS));

        MergeDiff.Result result = MergeDiff.merge(base, source, target);

        assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.kind()).isEqualTo("DELETE_MODIFY");
            assertThat(conflict.targetId()).isEqualTo(CLASSIFIER_ID);
            assertThat(conflict.field()).isNull();
            assertThat(conflict.baseValue()).isEqualTo("Order");
            assertThat(conflict.sourceValue()).isNull();
            assertThat(conflict.targetValue()).isEqualTo("RenamedOrder");
        });
    }

    @Test
    void independentlyCreatedElementsWithTheSameNameAndDifferentContentCollide() {
        SnapshotModel base = model();
        SnapshotModel source = model(classifier(CLASSIFIER_ID, "NewType", ClassifierKind.CLASS));
        SnapshotModel target = model(classifier(UUID.fromString("10000000-0000-0000-0000-000000000002"),
                "NewType", ClassifierKind.INTERFACE));

        MergeDiff.Result result = MergeDiff.merge(base, source, target);

        assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.kind()).isEqualTo("NAME_COLLISION");
            assertThat(conflict.targetType()).isEqualTo("CLASSIFIER");
            assertThat(conflict.targetName()).isEqualTo("NewType");
            assertThat(conflict.field()).isNull();
        });
    }

    @Test
    void associationClassAssignedToDifferentRelationshipsAcrossBranchesIsAFieldConflict() {
        SnapshotModel base = relationshipModel(
                relationship(FIRST_RELATIONSHIP_ID, "Enrollment", null),
                relationship(SECOND_RELATIONSHIP_ID, "Registration", null));
        SnapshotModel source = relationshipModel(
                relationship(FIRST_RELATIONSHIP_ID, "Enrollment", ASSOCIATION_CLASS_ID),
                relationship(SECOND_RELATIONSHIP_ID, "Registration", null));
        SnapshotModel target = relationshipModel(
                relationship(FIRST_RELATIONSHIP_ID, "Enrollment", null),
                relationship(SECOND_RELATIONSHIP_ID, "Registration", ASSOCIATION_CLASS_ID));

        MergeDiff.Result result = MergeDiff.merge(base, source, target);

        assertThat(result.conflicts()).hasSize(2);
        assertThat(result.conflicts()).extracting(MergeDiff.Conflict::targetId)
                .containsExactly(FIRST_RELATIONSHIP_ID, SECOND_RELATIONSHIP_ID);
        assertThat(result.conflicts()).allSatisfy(conflict -> {
            assertThat(conflict.kind()).isEqualTo("FIELD_CONFLICT");
            assertThat(conflict.targetType()).isEqualTo("RELATIONSHIP");
            assertThat(conflict.field()).isEqualTo("associationClassId");
            assertThat(conflict.baseValue()).isNull();
            assertThat(conflict.message()).contains("Enrollment", "Registration", ASSOCIATION_CLASS_ID.toString());
            if (conflict.targetId().equals(FIRST_RELATIONSHIP_ID)) {
                assertThat(conflict.targetName()).isEqualTo("Enrollment");
                assertThat(conflict.sourceValue()).isEqualTo(ASSOCIATION_CLASS_ID);
                assertThat(conflict.targetValue()).isNull();
            } else {
                assertThat(conflict.targetName()).isEqualTo("Registration");
                assertThat(conflict.sourceValue()).isNull();
                assertThat(conflict.targetValue()).isEqualTo(ASSOCIATION_CLASS_ID);
            }
        });
        assertThat(result.mergedSnapshot().relationships())
                .extracting(RelationshipResource::associationClassId)
                .containsExactlyInAnyOrder(ASSOCIATION_CLASS_ID, ASSOCIATION_CLASS_ID);
    }

    @Test
    void differentAssociationClassesCanBelongToDifferentRelationships() {
        SnapshotModel base = relationshipModel(
                relationship(FIRST_RELATIONSHIP_ID, "Enrollment", null),
                relationship(SECOND_RELATIONSHIP_ID, "Registration", null));
        SnapshotModel source = relationshipModel(
                relationship(FIRST_RELATIONSHIP_ID, "Enrollment", ASSOCIATION_CLASS_ID),
                relationship(SECOND_RELATIONSHIP_ID, "Registration", null));
        SnapshotModel target = relationshipModel(
                relationship(FIRST_RELATIONSHIP_ID, "Enrollment", null),
                relationship(SECOND_RELATIONSHIP_ID, "Registration", OTHER_ASSOCIATION_CLASS_ID));

        MergeDiff.Result result = MergeDiff.merge(base, source, target);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.mergedSnapshot().relationships())
                .extracting(RelationshipResource::associationClassId)
                .containsExactlyInAnyOrder(ASSOCIATION_CLASS_ID, OTHER_ASSOCIATION_CLASS_ID);
    }

    @Test
    void releasedOwnershipOnOneSideAllowsTheOtherSideToTransferTheClass() {
        SnapshotModel base = relationshipModel(
                relationship(FIRST_RELATIONSHIP_ID, "Enrollment", ASSOCIATION_CLASS_ID),
                relationship(SECOND_RELATIONSHIP_ID, "Registration", null));
        SnapshotModel source = relationshipModel(
                relationship(FIRST_RELATIONSHIP_ID, "Enrollment", null),
                relationship(SECOND_RELATIONSHIP_ID, "Registration", null));
        SnapshotModel target = relationshipModel(
                relationship(FIRST_RELATIONSHIP_ID, "Enrollment", null),
                relationship(SECOND_RELATIONSHIP_ID, "Registration", ASSOCIATION_CLASS_ID));

        MergeDiff.Result result = MergeDiff.merge(base, source, target);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.mergedSnapshot().relationships()).anySatisfy(relationship -> {
            assertThat(relationship.id()).isEqualTo(SECOND_RELATIONSHIP_ID);
            assertThat(relationship.associationClassId()).isEqualTo(ASSOCIATION_CLASS_ID);
        });
        assertThat(result.mergedSnapshot().relationships()).anySatisfy(relationship -> {
            assertThat(relationship.id()).isEqualTo(FIRST_RELATIONSHIP_ID);
            assertThat(relationship.associationClassId()).isNull();
        });
    }

    @Test
    void identicalChangesOnBothSidesAreNotConflicts() {
        SnapshotModel base = model(classifier(CLASSIFIER_ID, "Order", ClassifierKind.CLASS));
        SnapshotModel changed = model(classifier(CLASSIFIER_ID, "RenamedOrder", ClassifierKind.CLASS));

        MergeDiff.Result result = MergeDiff.merge(base, changed, changed);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.mergedSnapshot().classifiers()).containsExactly(
                classifier(CLASSIFIER_ID, "RenamedOrder", ClassifierKind.CLASS));
    }

    @Test
    void disjointAdditionsFromBothSidesAreIncludedWithoutConflicts() {
        ClassifierResource sourceAddition = classifier(CLASSIFIER_ID, "SourceType", ClassifierKind.CLASS);
        ClassifierResource targetAddition = classifier(
                UUID.fromString("10000000-0000-0000-0000-000000000002"), "TargetType", ClassifierKind.INTERFACE);

        MergeDiff.Result result = MergeDiff.merge(model(), model(sourceAddition), model(targetAddition));

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.mergedSnapshot().classifiers()).containsExactly(sourceAddition, targetAddition);
    }

    @Test
    void rangeBoundsSurviveAMergeAndOldSnapshotsReadThemAsNull() {
        com.diagramador.api.ModelResource.AttributeResource ranged = new com.diagramador.api.ModelResource.AttributeResource(
                UUID.fromString("40000000-0000-0000-0000-000000000001"), "tags", "ATTRIBUTE", "PUBLIC",
                com.diagramador.domain.MultiplicityKind.RANGE, null, null, "String", 0, 2, 5);
        com.diagramador.api.ModelResource.AttributeResource legacy = new com.diagramador.api.ModelResource.AttributeResource(
                UUID.fromString("40000000-0000-0000-0000-000000000002"), "name", "ATTRIBUTE", "PUBLIC",
                com.diagramador.domain.MultiplicityKind.EXACTLY_ONE, null, null, "String", 1);
        ClassifierResource owner = new ClassifierResource(CLASSIFIER_ID, "Order", ClassifierKind.CLASS, false, null,
                List.of(ranged, legacy), List.of(), List.of());

        MergeDiff.Result result = MergeDiff.merge(model(), model(owner), model());

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.mergedSnapshot().classifiers().get(0).attributes())
                .extracting(attribute -> attribute.lowerBound() + ".." + attribute.upperBound())
                .containsExactly("2..5", "null..null");
    }

    private static SnapshotModel model(ClassifierResource... classifiers) {
        return new SnapshotModel(List.of(), List.of(classifiers), List.of(), List.of());
    }

    private static SnapshotModel relationshipModel(RelationshipResource... relationships) {
        return new SnapshotModel(List.of(), List.of(), List.of(relationships), List.of());
    }

    private static RelationshipResource relationship(UUID id, String name, UUID associationClassId) {
        return new RelationshipResource(id, "ASSOCIATION", name, null, associationClassId, List.of(), List.of());
    }

    private static ClassifierResource classifier(UUID id, String name, ClassifierKind kind) {
        return new ClassifierResource(id, name, kind, kind == ClassifierKind.ABSTRACT_CLASS,
                null, List.of(), List.of(), List.of());
    }
}
