package com.diagramador.vcs;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.api.ModelResource.AttributeResource;
import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.api.ModelResource.OperationResource;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SnapshotDiffTest {

    private static final UUID ORDER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ATTRIBUTE_ID = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID OPERATION_ID = UUID.fromString("12000000-0000-0000-0000-000000000001");

    @Test
    void identicalSnapshotsProduceNoChanges() {
        SnapshotModel snapshot = model(order("Order", List.of(), List.of()));

        assertThat(SnapshotDiff.between(snapshot, snapshot)).isEmpty();
    }

    @Test
    void renamingAClassifierReportsOnlyTheNameField() {
        SnapshotModel before = model(order("Order", List.of(), List.of()));
        SnapshotModel after = model(order("SaleOrder", List.of(), List.of()));

        assertThat(SnapshotDiff.between(before, after)).singleElement().satisfies(change -> {
            assertThat(change.changeKind()).isEqualTo("MODIFIED");
            assertThat(change.targetType()).isEqualTo("CLASSIFIER");
            assertThat(change.targetId()).isEqualTo(ORDER_ID);
            assertThat(change.targetName()).isEqualTo("SaleOrder");
            assertThat(change.fields()).singleElement().satisfies(field -> {
                assertThat(field.field()).isEqualTo("name");
                assertThat(field.before()).isEqualTo("Order");
                assertThat(field.after()).isEqualTo("SaleOrder");
            });
        });
    }

    @Test
    void anAddedAttributeIsReportedWithItsOwnerAndItsVisibleFields() {
        SnapshotModel before = model(order("Order", List.of(), List.of()));
        SnapshotModel after = model(order("Order", List.of(attribute("total", "Integer")), List.of()));

        assertThat(SnapshotDiff.between(before, after)).singleElement().satisfies(change -> {
            assertThat(change.changeKind()).isEqualTo("ADDED");
            assertThat(change.targetType()).isEqualTo("ATTRIBUTE");
            assertThat(change.targetName()).isEqualTo("total");
            assertThat(change.ownerName()).isEqualTo("Order");
            assertThat(change.fields()).anySatisfy(field -> {
                assertThat(field.field()).isEqualTo("type");
                assertThat(field.before()).isNull();
                assertThat(field.after()).isEqualTo("Integer");
            });
            // Internal identifiers are not part of what a person reads in a diff.
            assertThat(change.fields()).noneSatisfy(field -> assertThat(field.field()).endsWith("Id"));
        });
    }

    @Test
    void changingAnAttributeTypeListsOnlyTheFieldThatChanged() {
        SnapshotModel before = model(order("Order", List.of(attribute("total", "String")), List.of()));
        SnapshotModel after = model(order("Order", List.of(attribute("total", "Integer")), List.of()));

        assertThat(SnapshotDiff.between(before, after)).singleElement().satisfies(change -> {
            assertThat(change.changeKind()).isEqualTo("MODIFIED");
            assertThat(change.targetType()).isEqualTo("ATTRIBUTE");
            assertThat(change.ownerName()).isEqualTo("Order");
            assertThat(change.fields()).singleElement().satisfies(field -> {
                assertThat(field.field()).isEqualTo("type");
                assertThat(field.before()).isEqualTo("String");
                assertThat(field.after()).isEqualTo("Integer");
            });
        });
    }

    @Test
    void aRemovedOperationKeepsItsNameAndOwner() {
        SnapshotModel before = model(order("Order", List.of(), List.of(operation("cancel"))));
        SnapshotModel after = model(order("Order", List.of(), List.of()));

        assertThat(SnapshotDiff.between(before, after)).singleElement().satisfies(change -> {
            assertThat(change.changeKind()).isEqualTo("REMOVED");
            assertThat(change.targetType()).isEqualTo("OPERATION");
            assertThat(change.targetName()).isEqualTo("cancel");
            assertThat(change.ownerName()).isEqualTo("Order");
        });
    }

    @Test
    void theFirstCommitHasNoPreviousSnapshotSoEverythingIsAdded() {
        SnapshotModel after = model(order("Order", List.of(attribute("total", "Integer")), List.of()));

        assertThat(SnapshotDiff.between(null, after))
                .extracting(change -> change.changeKind() + " " + change.targetType())
                .containsExactly("ADDED CLASSIFIER", "ADDED ATTRIBUTE");
    }

    private static SnapshotModel model(ClassifierResource... classifiers) {
        return new SnapshotModel(List.of(), List.of(classifiers), List.of(), List.of());
    }

    private static ClassifierResource order(String name, List<AttributeResource> attributes,
            List<OperationResource> operations) {
        return new ClassifierResource(ORDER_ID, name, ClassifierKind.CLASS, false, null, attributes, operations,
                List.of());
    }

    private static AttributeResource attribute(String name, String type) {
        return new AttributeResource(ATTRIBUTE_ID, name, "ATTRIBUTE", "PRIVATE", MultiplicityKind.EXACTLY_ONE,
                null, null, type, 0);
    }

    private static OperationResource operation(String name) {
        return new OperationResource(OPERATION_ID, name, "PUBLIC", false, false, null, "void", 0, List.of());
    }
}
