package com.diagramador.vcs;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.api.ModelResource.NoteLinkResource;
import com.diagramador.api.ModelResource.NoteResource;
import com.diagramador.api.ModelResource.RelationshipResource;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.TargetType;
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

    @Test
    void stereotypesSurviveAMergeAndAnOldSnapshotReadsThemAsNull() {
        com.diagramador.api.ModelResource.AttributeResource tagged = new com.diagramador.api.ModelResource.AttributeResource(
                UUID.fromString("40000000-0000-0000-0000-000000000011"), "code", "ATTRIBUTE", "PUBLIC",
                com.diagramador.domain.MultiplicityKind.EXACTLY_ONE, null, null, "String", 0, null, null, "ID");
        com.diagramador.api.ModelResource.OperationResource query = new com.diagramador.api.ModelResource.OperationResource(
                UUID.fromString("50000000-0000-0000-0000-000000000011"), "load", "PUBLIC", false, false, null, null, 0,
                List.of(), "QUERY");
        ClassifierResource stereotyped = new ClassifierResource(CLASSIFIER_ID, "Order", ClassifierKind.CLASS, false,
                null, List.of(tagged), List.of(query), List.of(), null, null, null, "ENTITY");
        ClassifierResource base = new ClassifierResource(CLASSIFIER_ID, "Order", ClassifierKind.CLASS, false, null,
                List.of(), List.of(), List.of());

        MergeDiff.Result result = MergeDiff.merge(model(base), model(stereotyped), model(base));

        assertThat(result.conflicts()).isEmpty();
        ClassifierResource merged = result.mergedSnapshot().classifiers().get(0);
        assertThat(merged.stereotype()).isEqualTo("ENTITY");
        assertThat(merged.attributes()).singleElement()
                .satisfies(attribute -> assertThat(attribute.stereotype()).isEqualTo("ID"));
        assertThat(merged.operations()).singleElement()
                .satisfies(operation -> assertThat(operation.stereotype()).isEqualTo("QUERY"));
        assertThat(base.stereotype()).as("a snapshot written before stereotypes read null").isNull();
    }

    @Test
    void attributeFlagsAndEndPropertiesSurviveAMergeAndDivergentOnesConflict() {
        UUID attributeId = UUID.fromString("40000000-0000-0000-0000-000000000021");
        UUID relationshipId = UUID.fromString("60000000-0000-0000-0000-000000000021");
        UUID otherId = UUID.fromString("40000000-0000-0000-0000-000000000022");
        ClassifierResource base = classifierWith(attributeId, false, false);
        ClassifierResource flagged = classifierWith(attributeId, true, true);

        MergeDiff.Result merged = MergeDiff.merge(model(base), model(flagged), model(base));
        assertThat(merged.conflicts()).isEmpty();
        assertThat(merged.mergedSnapshot().classifiers().get(0).attributes()).singleElement()
                .satisfies(attribute -> {
                    assertThat(attribute.isStatic()).isTrue();
                    assertThat(attribute.isDerived()).isTrue();
                });
        assertThat(base.attributes().get(0).isStatic()).as("an old snapshot reads false").isFalse();

        ClassifierResource staticOnly = classifierWith(attributeId, true, false);
        ClassifierResource derivedOnly = classifierWith(attributeId, false, true);
        MergeDiff.Result both = MergeDiff.merge(model(base), model(staticOnly), model(derivedOnly));
        assertThat(both.conflicts()).as("independent flags merge").isEmpty();
        assertThat(both.mergedSnapshot().classifiers().get(0).attributes()).singleElement()
                .satisfies(attribute -> {
                    assertThat(attribute.isStatic()).isTrue();
                    assertThat(attribute.isDerived()).isTrue();
                });

        com.diagramador.api.ModelResource.RelationshipEndResource plainEnd =
                new com.diagramador.api.ModelResource.RelationshipEndResource(otherId, CLASSIFIER_ID, "r",
                        com.diagramador.domain.MultiplicityKind.EXACTLY_ONE,
                        com.diagramador.domain.NavigationKind.UNSPECIFIED, "none", (short) 1);
        com.diagramador.api.ModelResource.RelationshipEndResource markedEnd =
                new com.diagramador.api.ModelResource.RelationshipEndResource(otherId, CLASSIFIER_ID, "r",
                        com.diagramador.domain.MultiplicityKind.EXACTLY_ONE,
                        com.diagramador.domain.NavigationKind.UNSPECIFIED, "none", (short) 1, null, null, true, true);
        MergeDiff.Result endMerge = MergeDiff.merge(
                relationshipModel(withEnd(relationshipId, plainEnd)), relationshipModel(withEnd(relationshipId, markedEnd)),
                relationshipModel(withEnd(relationshipId, plainEnd)));
        assertThat(endMerge.conflicts()).isEmpty();
        assertThat(endMerge.mergedSnapshot().relationships().get(0).ends()).singleElement()
                .satisfies(end -> {
                    assertThat(end.isOrdered()).isTrue();
                    assertThat(end.isUnique()).isTrue();
                });
    }

    private static RelationshipResource withEnd(UUID relationshipId,
            com.diagramador.api.ModelResource.RelationshipEndResource end) {
        return new RelationshipResource(relationshipId, "ASSOCIATION", "r", List.of(end), List.of());
    }

    private static ClassifierResource classifierWith(UUID attributeId, boolean isStatic, boolean isDerived) {
        com.diagramador.api.ModelResource.AttributeResource attribute =
                new com.diagramador.api.ModelResource.AttributeResource(attributeId, "counter", "ATTRIBUTE", "PUBLIC",
                        com.diagramador.domain.MultiplicityKind.EXACTLY_ONE, null, null, "String", 0, null, null, null,
                        isStatic, isDerived);
        return new ClassifierResource(CLASSIFIER_ID, "Order", ClassifierKind.CLASS, false, null, List.of(attribute),
                List.of(), List.of());
    }

    @Test
    void divergentStereotypesOfTheSameClassifierAreAFieldConflict() {
        ClassifierResource base = new ClassifierResource(CLASSIFIER_ID, "Order", ClassifierKind.CLASS, false, null,
                List.of(), List.of(), List.of());
        ClassifierResource ours = new ClassifierResource(CLASSIFIER_ID, "Order", ClassifierKind.CLASS, false, null,
                List.of(), List.of(), List.of(), null, null, null, "ENTITY");
        ClassifierResource theirs = new ClassifierResource(CLASSIFIER_ID, "Order", ClassifierKind.CLASS, false, null,
                List.of(), List.of(), List.of(), null, null, null, "SERVICE");

        MergeDiff.Result result = MergeDiff.merge(model(base), model(ours), model(theirs));

        assertThat(result.conflicts()).singleElement()
                .satisfies(conflict -> assertThat(conflict.field()).isEqualTo("stereotype"));
    }

    private static final UUID NOTE_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_NOTE_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_CLASSIFIER_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Test
    void notesAddedOnBothSidesAreBothKept() {
        SnapshotModel base = noted(List.of(classifier(CLASSIFIER_ID, "Order", ClassifierKind.CLASS)), List.of());
        SnapshotModel source = noted(base.classifiers(), List.of(note(NOTE_ID, "ours", CLASSIFIER_ID)));
        SnapshotModel target = noted(base.classifiers(), List.of(note(OTHER_NOTE_ID, "theirs")));

        MergeDiff.Result result = MergeDiff.merge(base, source, target);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.mergedSnapshot().notes()).containsExactly(note(NOTE_ID, "ours", CLASSIFIER_ID),
                note(OTHER_NOTE_ID, "theirs"));
    }

    @Test
    void aBodyChangeOnOneSideAndALinkOnTheOtherMergeWithoutConflict() {
        List<ClassifierResource> classifiers = List.of(classifier(CLASSIFIER_ID, "Order", ClassifierKind.CLASS),
                classifier(OTHER_CLASSIFIER_ID, "Customer", ClassifierKind.CLASS));
        SnapshotModel base = noted(classifiers, List.of(note(NOTE_ID, "body", CLASSIFIER_ID)));
        SnapshotModel source = noted(classifiers, List.of(note(NOTE_ID, "edited", CLASSIFIER_ID)));
        SnapshotModel target = noted(classifiers,
                List.of(note(NOTE_ID, "body", CLASSIFIER_ID, OTHER_CLASSIFIER_ID)));

        MergeDiff.Result result = MergeDiff.merge(base, source, target);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.mergedSnapshot().notes())
                .containsExactly(note(NOTE_ID, "edited", CLASSIFIER_ID, OTHER_CLASSIFIER_ID));
    }

    @Test
    void divergentBodiesOfTheSameNoteAreAFieldConflict() {
        List<ClassifierResource> classifiers = List.of(classifier(CLASSIFIER_ID, "Order", ClassifierKind.CLASS));
        MergeDiff.Result result = MergeDiff.merge(
                noted(classifiers, List.of(note(NOTE_ID, "base"))),
                noted(classifiers, List.of(note(NOTE_ID, "ours"))),
                noted(classifiers, List.of(note(NOTE_ID, "theirs"))));

        assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.kind()).isEqualTo("FIELD_CONFLICT");
            assertThat(conflict.targetType()).isEqualTo("NOTE");
            assertThat(conflict.targetId()).isEqualTo(NOTE_ID);
            assertThat(conflict.field()).isEqualTo("body");
            assertThat(conflict.sourceValue()).isEqualTo("ours");
            assertThat(conflict.targetValue()).isEqualTo("theirs");
        });
    }

    @Test
    void aLinkToAClassifierDeletedOnTheOtherSideDoesNotSurviveTheMerge() {
        ClassifierResource order = classifier(CLASSIFIER_ID, "Order", ClassifierKind.CLASS);
        ClassifierResource customer = classifier(OTHER_CLASSIFIER_ID, "Customer", ClassifierKind.CLASS);
        SnapshotModel base = noted(List.of(order, customer), List.of(note(NOTE_ID, "n")));
        SnapshotModel source = noted(List.of(customer), List.of(note(NOTE_ID, "n")));
        // The other branch annotates the very class this branch deleted.
        SnapshotModel target = noted(List.of(order, customer), List.of(note(NOTE_ID, "n", CLASSIFIER_ID)));

        MergeDiff.Result result = MergeDiff.merge(base, source, target);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.mergedSnapshot().classifiers()).extracting(ClassifierResource::id)
                .containsExactly(OTHER_CLASSIFIER_ID);
        assertThat(result.mergedSnapshot().notes()).containsExactly(note(NOTE_ID, "n"));
    }

    @Test
    void theCommitDiffListsANoteAndItsLinkByTheirNames() {
        ClassifierResource order = classifier(CLASSIFIER_ID, "Order", ClassifierKind.CLASS);
        SnapshotModel before = noted(List.of(order), List.of());
        SnapshotModel after = noted(List.of(order), List.of(note(NOTE_ID, "Check the totals", CLASSIFIER_ID)));

        var changes = SnapshotDiff.between(before, after);

        assertThat(changes).extracting(VcsResources.ElementChange::targetType).containsExactly("NOTE", "NOTE_LINK");
        assertThat(changes.get(0).changeKind()).isEqualTo("ADDED");
        assertThat(changes.get(0).targetName()).isEqualTo("Check the totals");
        assertThat(changes.get(1).targetName()).isEqualTo("Order");
        assertThat(changes.get(1).ownerName()).isEqualTo("Check the totals");
    }

    private static SnapshotModel noted(List<ClassifierResource> classifiers, List<NoteResource> notes) {
        return new SnapshotModel(List.of(), classifiers, List.of(), List.of(), notes);
    }

    private static NoteResource note(UUID id, String body, UUID... classifierIds) {
        return new NoteResource(id, body, java.util.Arrays.stream(classifierIds)
                .map(classifierId -> new NoteLinkResource(TargetType.CLASSIFIER, classifierId)).toList());
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
