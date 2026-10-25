package com.diagramador.vcs;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.domain.ClassifierKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VcsMergeDiffTest {

    private static final UUID CLASSIFIER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

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

    private static SnapshotModel model(ClassifierResource... classifiers) {
        return new SnapshotModel(List.of(), List.of(classifiers), List.of(), List.of());
    }

    private static ClassifierResource classifier(UUID id, String name, ClassifierKind kind) {
        return new ClassifierResource(id, name, kind, kind == ClassifierKind.ABSTRACT_CLASS,
                null, List.of(), List.of(), List.of());
    }
}
