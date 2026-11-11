package com.diagramador.xmi;

import static com.diagramador.xmi.XmiTestReadings.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.api.ModelResource;
import com.diagramador.domain.ClassifierKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class XmiRepairTest {

    private final XmiRepair repair = new XmiRepair();
    private final XmiImportValidator validator = new XmiImportValidator();

    private static long lossCount(XmiRepair.Result result, String name) {
        return result.reading().losses().stream().filter(loss -> loss.name().equals(name))
                .mapToLong(XmiReadingResult.Loss::count).sum();
    }

    private void assertClean(XmiRepair.Result result) {
        assertThat(validator.validate(result.reading(), XmiImportMode.REEMPLAZAR,
                XmiImportValidator.ExistingModel.EMPTY))
                .filteredOn(issue -> issue.severity() == XmiImportIssue.Severity.BLOCKER).isEmpty();
    }

    @Test
    void aCleanReadingIsReturnedUnchangedWithoutRepairs() {
        XmiReadingResult reading = clean();

        XmiRepair.Result result = repair.repair(reading);

        assertThat(result.repairs()).isEmpty();
        assertThat(result.reading()).isEqualTo(reading);
    }

    @Test
    void severalDiagramsKeepTheDefaultOneAndRecordTheLoss() {
        var first = view(100, "First", false, List.of(geometry(500, 1, 200, 100)), List.of());
        var second = view(101, "Second", true, List.of(geometry(501, 2, 200, 100)), List.of());
        XmiReadingResult reading = reading(List.of(clazz(1, "Alpha"), clazz(2, "Beta")), List.of(),
                List.of(first, second), List.of());

        XmiRepair.Result result = repair.repair(reading);

        assertThat(result.reading().model().views()).singleElement()
                .satisfies(view -> assertThat(view.name()).isEqualTo("Second"));
        assertThat(result.reading().diagrams()).singleElement()
                .satisfies(diagram -> assertThat(diagram.id()).isEqualTo(id(101)));
        assertThat(lossCount(result, "extra_diagrams_not_imported")).isEqualTo(1);
        assertThat(result.repairs()).extracting(XmiRepair.Repair::code).contains("extra_diagrams_not_imported");
        assertClean(result);
    }

    @Test
    void severalDiagramsWithoutADefaultKeepTheFirstOne() {
        var first = view(100, "First", false, List.of(), List.of());
        var second = view(101, "Second", false, List.of(), List.of());

        XmiRepair.Result result = repair
                .repair(reading(List.of(clazz(1, "Alpha")), List.of(), List.of(first, second), List.of()));

        assertThat(result.reading().model().views()).singleElement()
                .satisfies(view -> assertThat(view.name()).isEqualTo("First"));
        assertThat(result.reading().model().views().get(0).isDefault()).isTrue();
    }

    @Test
    void noDiagramSynthesizesTheMandatoryDefaultViewDeterministically() {
        XmiReadingResult reading = reading(List.of(clazz(1, "Alpha"), clazz(2, "Beta"),
                classifier(3, "pkg", ClassifierKind.PACKAGE, null)), List.of(), List.of(), List.of());

        XmiRepair.Result result = repair.repair(reading);

        assertThat(result.reading().model().views()).singleElement().satisfies(view -> {
            assertThat(view.isDefault()).isTrue();
            assertThat(view.name()).isNotBlank();
            assertThat(view.elementGeometry()).hasSize(2).allSatisfy(item -> {
                assertThat(item.w()).isPositive();
                assertThat(item.h()).isPositive();
            });
        });
        assertThat(result.reading().diagrams()).hasSize(1);
        assertThat(lossCount(result, "diagram_synthesized")).isEqualTo(1);
        assertThat(repair.repair(reading)).isEqualTo(result);
        assertClean(result);
    }

    @Test
    void figuresOfMissingClassifiersAreDroppedAndCounted() {
        var view = view(100, "Main", true, List.of(geometry(500, 1, 200, 100), geometry(501, 77, 200, 100)),
                List.of());
        var diagram = diagram(view, List.of(figure(1, 1), figure(2, 77)), List.of());
        XmiReadingResult reading = reading(List.of(clazz(1, "Alpha")), List.of(), List.of(view), List.of(diagram));

        XmiRepair.Result result = repair.repair(reading);

        assertThat(result.reading().model().views().get(0).elementGeometry()).hasSize(1);
        assertThat(result.reading().diagrams().get(0).figures()).hasSize(1);
        assertThat(lossCount(result, "figures_dropped_missing_classifier")).isEqualTo(2);
        assertClean(result);
    }

    @Test
    void relationshipsWithAnEndOnAMissingClassifierAreDroppedWithTheirPathsAndConnectors() {
        var path = new ModelResource.ConnectorPathResource(id(600), id(10), 0, 5, 5);
        var view = view(100, "Main", true, List.of(geometry(500, 1, 200, 100)), List.of(path));
        var diagram = diagram(view, List.of(), List.of(connector(1, 10)));
        XmiReadingResult reading = reading(List.of(clazz(1, "Alpha")),
                List.of(association(10, 1, 99), association(11, 1, 1)), List.of(view), List.of(diagram));

        XmiRepair.Result result = repair.repair(reading);

        assertThat(result.reading().model().relationships()).extracting(ModelResource.RelationshipResource::id)
                .containsExactly(id(11));
        assertThat(result.reading().model().views().get(0).connectorPath()).isEmpty();
        assertThat(result.reading().diagrams().get(0).connectors()).isEmpty();
        assertThat(lossCount(result, "relationships_dropped_missing_classifier")).isEqualTo(1);
        assertClean(result);
    }

    @Test
    void degenerateGeometryIsClampedToTheMinimumSize() {
        var view = view(100, "Main", true, List.of(geometry(500, 1, 0, -5)), List.of());

        XmiRepair.Result result = repair
                .repair(reading(List.of(clazz(1, "Alpha")), List.of(), List.of(view), List.of()));

        assertThat(result.reading().model().views().get(0).elementGeometry()).singleElement().satisfies(item -> {
            assertThat(item.w()).isEqualTo(XmiRepair.MIN_WIDTH);
            assertThat(item.h()).isEqualTo(XmiRepair.MIN_HEIGHT);
        });
        assertThat(lossCount(result, "geometry_clamped")).isEqualTo(1);
        assertClean(result);
    }

    @Test
    void duplicateClassifierIdsAreRegeneratedDeterministically() {
        XmiReadingResult reading = reading(List.of(clazz(1, "Alpha"), clazz(1, "Beta")), List.of(),
                List.of(view(100, "Main", true, List.of(geometry(500, 1, 200, 100)), List.of())), List.of());

        XmiRepair.Result result = repair.repair(reading);

        assertThat(result.reading().model().classifiers()).extracting(ModelResource.ClassifierResource::id)
                .doesNotHaveDuplicates().contains(id(1));
        assertThat(result.reading().model().classifiers().get(0).id()).isEqualTo(id(1));
        assertThat(lossCount(result, "duplicate_classifier_ids_regenerated")).isEqualTo(1);
        assertThat(repair.repair(reading)).isEqualTo(result);
        assertClean(result);
    }

    @Test
    void aClassNestedUnderANonPackageClassifierIsHoistedToTheNearestPackage() {
        XmiReadingResult reading = reading(List.of(
                classifier(1, "pkg", ClassifierKind.PACKAGE, null),
                classifier(2, "Outer", ClassifierKind.CLASS, 1L),
                classifier(3, "Inner", ClassifierKind.CLASS, 2L),
                classifier(4, "Lost", ClassifierKind.CLASS, 77L)), List.of(),
                List.of(view(100, "Main", true, List.of(), List.of())), List.of());

        XmiRepair.Result result = repair.repair(reading);

        assertThat(result.reading().model().classifiers()).filteredOn(item -> item.name().equals("Inner"))
                .singleElement().satisfies(item -> assertThat(item.namespaceId()).isEqualTo(id(1)));
        assertThat(result.reading().model().classifiers()).filteredOn(item -> item.name().equals("Lost"))
                .singleElement().satisfies(item -> assertThat(item.namespaceId()).isNull());
        assertThat(lossCount(result, "namespace_hoisted_to_package")).isEqualTo(2);
        assertClean(result);
    }

    @Test
    void aBlankClassifierNameGetsAPlaceholder() {
        XmiReadingResult reading = reading(List.of(clazz(1, "  "), clazz(2, ""), clazz(3, "Gamma")), List.of(),
                List.of(view(100, "Main", true, List.of(), List.of())), List.of());

        XmiRepair.Result result = repair.repair(reading);

        assertThat(result.reading().model().classifiers()).extracting(ModelResource.ClassifierResource::name)
                .containsExactly("SinNombre-1", "SinNombre-2", "Gamma");
        assertThat(lossCount(result, "blank_classifier_name_replaced")).isEqualTo(2);
        assertClean(result);
    }

    @Test
    void nameCollisionsAreNotAutoRepaired() {
        XmiReadingResult reading = reading(List.of(clazz(1, "Same"), clazz(2, "Same")), List.of(),
                List.of(view(100, "Main", true, List.of(), List.of())), List.of());

        XmiRepair.Result result = repair.repair(reading);

        assertThat(result.repairs()).isEmpty();
        assertThat(result.reading()).isEqualTo(reading);
    }
}
