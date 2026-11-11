package com.diagramador.xmi;

import static com.diagramador.xmi.XmiTestReadings.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.api.ModelResource;
import java.util.List;
import org.junit.jupiter.api.Test;

class XmiResolutionApplierTest {

    private final XmiResolutionApplier applier = new XmiResolutionApplier();

    private static XmiReadingResult twoSameNames() {
        return reading(List.of(clazz(1, "Same"), clazz(2, "Same")), List.of(association(10, 1, 2)),
                List.of(view(100, "Main", true, List.of(geometry(500, 1, 200, 100), geometry(501, 2, 200, 100)),
                        List.of())),
                List.of());
    }

    @Test
    void renameChangesOnlyTheNameOfTheSubjectClassifier() {
        XmiRepair.Result result = applier.apply(twoSameNames(), List.of(
                new XmiResolution(id(2).toString(), XmiResolution.Action.RENAME, "Other")));

        assertThat(result.reading().model().classifiers()).extracting(ModelResource.ClassifierResource::name)
                .containsExactly("Same", "Other");
        assertThat(result.reading().model().relationships()).hasSize(1);
        assertThat(result.repairs()).extracting(XmiRepair.Repair::code).contains("resolution_renamed");
    }

    @Test
    void skipRemovesTheClassifierAndEverythingThatDependsOnIt() {
        XmiRepair.Result result = applier.apply(twoSameNames(), List.of(
                new XmiResolution(id(2).toString(), XmiResolution.Action.SKIP, null)));

        XmiReadingResult after = result.reading();
        assertThat(after.model().classifiers()).extracting(ModelResource.ClassifierResource::id).containsExactly(id(1));
        assertThat(after.model().relationships()).isEmpty();
        assertThat(after.model().views().get(0).elementGeometry()).hasSize(1);
        assertThat(after.losses()).extracting(XmiReadingResult.Loss::name)
                .contains("classifiers_skipped_by_resolution", "relationships_dropped_missing_classifier");
    }

    @Test
    void renamingTheDiagramChangesTheViewAndItsDiagram() {
        XmiRepair.Result result = applier.apply(clean(), List.of(
                new XmiResolution(id(100).toString(), XmiResolution.Action.RENAME, "Main (importado)")));

        assertThat(result.reading().model().views().get(0).name()).isEqualTo("Main (importado)");
        assertThat(result.reading().diagrams().get(0).name()).isEqualTo("Main (importado)");
    }

    @Test
    void keepViewMakesTheChosenDiagramTheDefaultOneToKeep() {
        XmiReadingResult reading = reading(List.of(clazz(1, "Alpha")), List.of(), List.of(
                view(100, "A", true, List.of(), List.of()), view(101, "B", false, List.of(), List.of())), List.of());

        XmiRepair.Result resolved = applier.apply(reading, List.of(
                new XmiResolution(id(101).toString(), XmiResolution.Action.KEEP_VIEW, null)));
        XmiRepair.Result repaired = new XmiRepair().repair(resolved.reading());

        assertThat(repaired.reading().model().views()).singleElement()
                .satisfies(view -> assertThat(view.id()).isEqualTo(id(101)));
    }

    @Test
    void anUnknownSubjectOrAnIncompleteResolutionChangesNothing() {
        XmiReadingResult reading = twoSameNames();

        XmiRepair.Result result = applier.apply(reading, List.of(
                new XmiResolution(id(55).toString(), XmiResolution.Action.SKIP, null),
                new XmiResolution(id(2).toString(), XmiResolution.Action.RENAME, " ")));

        assertThat(result.reading()).isEqualTo(reading);
        assertThat(result.repairs()).isEmpty();
    }
}
