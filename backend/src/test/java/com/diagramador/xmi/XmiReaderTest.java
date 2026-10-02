package com.diagramador.xmi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.diagramador.api.ModelResource;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.RelationshipKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class XmiReaderTest {

    private static final Path FIXTURE = Path.of("..", "docs", "fixtures", "xmi", "Prueba3.xml");
    private static final Path SECOND_EA_FILE = Path.of("C:/Users/HP/Downloads/ea-ref-with-diagram.xmi");

    private final XmiReader reader = new XmiReader();

    @Test
    void readsEveryMeasuredConstructInTheCanonicalEaFixture() throws Exception {
        byte[] bytes = Files.readAllBytes(FIXTURE);
        XmiReadingResult result = reader.read(bytes);
        ModelResource model = result.model();

        assertThat(model.classifiers()).hasSize(11);
        assertThat(model.classifiers()).filteredOn(c -> c.kind() == ClassifierKind.CLASS).hasSize(8);
        assertThat(model.classifiers()).filteredOn(c -> c.kind() == ClassifierKind.ABSTRACT_CLASS).isEmpty();
        assertThat(model.classifiers()).filteredOn(c -> c.kind() == ClassifierKind.INTERFACE).hasSize(1);
        assertThat(model.classifiers()).filteredOn(c -> c.kind() == ClassifierKind.ENUMERATION).hasSize(1);
        assertThat(model.classifiers()).filteredOn(c -> c.kind() == ClassifierKind.PACKAGE).hasSize(1);
        assertThat(model.classifiers().stream().mapToInt(c -> c.attributes().size()).sum()).isEqualTo(23);
        assertThat(model.classifiers().stream().filter(c -> c.kind() == ClassifierKind.ENUMERATION)
                .flatMap(c -> c.attributes().stream()).map(ModelResource.AttributeResource::name))
                .containsExactly("PENDIENTE", "CONFIRMADA", "CANCELADA", "COMPLETADA");
        assertThat(model.relationships()).hasSize(8);
        assertThat(model.relationships()).filteredOn(r -> Set.of(RelationshipKind.ASSOCIATION.name(),
                RelationshipKind.AGGREGATION.name(), RelationshipKind.COMPOSITION.name()).contains(r.kind()))
                .hasSize(6);
        assertThat(model.relationships()).filteredOn(r -> RelationshipKind.GENERALIZATION.name().equals(r.kind()))
                .hasSize(1);
        assertThat(model.relationships()).filteredOn(r -> RelationshipKind.INTERFACE_REALIZATION.name().equals(r.kind()))
                .hasSize(1);
        assertThat(model.relationships()).allSatisfy(r -> assertThat(r.ends()).hasSize(2));

        assertThat(model.views()).hasSize(1);
        assertThat(model.views().get(0).elementGeometry()).hasSize(10);
        assertThat(model.views().get(0).connectorPath()).isEmpty();
        assertThat(result.diagrams()).hasSize(1);
        assertThat(result.diagrams().get(0).figures()).hasSize(10);
        assertThat(result.diagrams().get(0).connectors()).hasSize(8);
        assertThat(result.diagrams().get(0).connectors()).filteredOn(c -> c.sourceDuid() != null
                && c.targetDuid() != null).hasSize(1);
        assertThat(result.diagrams().get(0).connectors()).filteredOn(c -> "622:-260$".equals(c.path()))
                .hasSize(1);
        assertThat(result.diagrams().get(0).connectors()).flatExtracting(
                c -> List.of(c.source(), c.target())).allSatisfy(end ->
                        assertThat(end.visibility()).isEqualTo("PUBLIC"));
        assertThat(model.relationships().stream().flatMap(r -> r.ends().stream())
                .map(ModelResource.RelationshipEndResource::navigability))
                .contains(com.diagramador.domain.NavigationKind.UNSPECIFIED,
                        com.diagramador.domain.NavigationKind.NAVIGABLE);
        assertThat(result.disagreements()).filteredOn(d -> d.kind().equals("navigability")).hasSize(2);
        assertThat(result.disagreements()).filteredOn(d -> d.kind().equals("multiplicity")).isEmpty();
        assertThat(result.documentation().values()).anyMatch(note -> note.contains("didáctica"));
        assertThat(result.losses()).containsExactly(
                new XmiReadingResult.Loss("attribute_layout_bounds", 21),
                new XmiReadingResult.Loss("attribute_layout_containment", 21),
                new XmiReadingResult.Loss("attribute_layout_coords", 21),
                new XmiReadingResult.Loss("connector_path_not_interpreted", 1),
                new XmiReadingResult.Loss("connector_vendor_geometry_not_interpreted", 1),
                new XmiReadingResult.Loss("diagram_connector_anchors_missing", 7),
                new XmiReadingResult.Loss("navigability_disagreement", 2),
                new XmiReadingResult.Loss("unresolved_relationship_end", 2),
                new XmiReadingResult.Loss("unresolved_type_reference", 4));

        XmiReadingResult repeated = reader.read(bytes);
        assertThat(repeated).isEqualTo(result);
    }

    @Test
    void reportsARealSemanticAndExtensionMultiplicityDisagreement() throws Exception {
        java.nio.charset.Charset encoding = java.nio.charset.Charset.forName("windows-1252");
        String source = new String(Files.readAllBytes(FIXTURE), encoding);
        String changed = source.replaceFirst("multiplicity=\\\"1\\\" aggregation=\\\"none\\\"",
                "multiplicity=\\\"0..*\\\" aggregation=\\\"none\\\"");
        assertThat(changed).isNotEqualTo(source);

        XmiReadingResult result = reader.read(changed.getBytes(encoding));

        assertThat(result.disagreements()).filteredOn(d -> d.kind().equals("multiplicity"))
                .singleElement().satisfies(disagreement -> {
                    assertThat(disagreement.semanticValue()).isEqualTo("1..1");
                    assertThat(disagreement.extensionValue()).isEqualTo("0..*");
                });
        assertThat(result.losses()).contains(new XmiReadingResult.Loss("multiplicity_disagreement", 1));
    }

    @Test
    void readsTheIndependentEaFileWithoutAssumingOurUuidDialect() throws Exception {
        assumeTrue(Files.isRegularFile(SECOND_EA_FILE), "the independently measured EA file is not installed");
        XmiReadingResult result = reader.read(Files.readAllBytes(SECOND_EA_FILE));

        assertThat(result.model().classifiers()).hasSize(6);
        assertThat(result.model().classifiers()).filteredOn(c -> c.kind() == ClassifierKind.CLASS).hasSize(5);
        assertThat(result.model().classifiers()).filteredOn(c -> c.kind() == ClassifierKind.PACKAGE).hasSize(1);
        assertThat(result.model().relationships()).hasSize(4);
        assertThat(result.model().relationships()).allSatisfy(r -> {
            assertThat(r.kind()).isEqualTo(RelationshipKind.ASSOCIATION.name());
            assertThat(r.ends()).hasSize(2);
        });
        assertThat(result.diagrams()).hasSize(1);
        assertThat(result.diagrams().get(0).figures()).hasSize(5);
        assertThat(result.diagrams().get(0).connectors()).hasSize(4);
        assertThat(result.diagrams().get(0).connectors()).allSatisfy(connector -> {
            assertThat(connector.sourceDuid()).isNull();
            assertThat(connector.targetDuid()).isNull();
            assertThat(connector.path()).isEmpty();
        });
        assertThat(result.losses()).containsExactly(
                new XmiReadingResult.Loss("attribute_layout_bounds", 4),
                new XmiReadingResult.Loss("attribute_layout_containment", 4),
                new XmiReadingResult.Loss("attribute_layout_coords", 4),
                new XmiReadingResult.Loss("diagram_connector_anchors_missing", 4));
    }

    @Test
    void rejectsMalformedAndUnsafeXmlInsteadOfReturningAnEmptyModel() {
        assertThatThrownBy(() -> reader.read("<xmi:XMI".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///not-read'>]>"
                + "<xmi:XMI xmlns:xmi='http://schema.omg.org/spec/XMI/2.1'>&e;</xmi:XMI>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
