package com.diagramador.xmi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramador.api.ModelResource;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.RelationshipKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class XmiReaderTest {

    private static final Path FIXTURE = Path.of("..", "docs", "fixtures", "xmi", "Prueba3.xml");
    private static final Path EA_INDEPENDENT = Path.of("..", "docs", "fixtures", "xmi",
            "ea-independent-with-diagram.xmi");
    private static final Path EA_ROUND_TRIP = Path.of("..", "docs", "fixtures", "xmi", "ea-roundtrip-uml-2.1.xml");
    private static final Path EA_DEFAULT_DIALECT = Path.of("..", "docs", "fixtures", "xmi",
            "ea-export-default-1.1.xml");
    private static final Path EA_UML_22 = Path.of("..", "docs", "fixtures", "xmi", "ea-export-uml-2.2.xml");

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
        // Written by the Enterprise Architect exporter from a different repository, and committed since E1: this test
        // used to skip silently because its only input lived in a user download folder.
        XmiReadingResult result = reader.read(Files.readAllBytes(EA_INDEPENDENT));

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
    void readsTheEaRoundTripExportOfOurOwnModelWithItsAnchorsResolved() throws Exception {
        // EA wrote this file with its own exporter, from the model this project emitted and EA then imported. Its
        // identifiers are ours because the emitter declares the EA dialect marker. See section 11.2 of the plan.
        XmiReadingResult result = reader.read(Files.readAllBytes(EA_ROUND_TRIP));
        ModelResource model = result.model();

        assertThat(model.classifiers()).hasSize(9);
        assertThat(model.classifiers()).filteredOn(c -> c.kind() == ClassifierKind.PACKAGE).hasSize(3);
        assertThat(model.classifiers()).filteredOn(c -> c.kind() == ClassifierKind.CLASS).hasSize(3);
        assertThat(model.classifiers()).filteredOn(c -> c.kind() == ClassifierKind.ABSTRACT_CLASS)
                .singleElement().satisfies(c -> assertThat(c.name()).isEqualTo("AbstractBase"));
        assertThat(model.classifiers()).filteredOn(c -> c.kind() == ClassifierKind.INTERFACE)
                .singleElement().satisfies(c -> assertThat(c.name()).isEqualTo("Contract"));
        assertThat(model.classifiers()).filteredOn(c -> c.kind() == ClassifierKind.ENUMERATION)
                .singleElement().satisfies(c -> assertThat(c.name()).isEqualTo("Status"));
        assertThat(model.classifiers()).filteredOn(c -> "Feature".equals(c.name()))
                .singleElement().satisfies(c -> assertThat(c.attributes()).hasSize(4));

        assertThat(model.relationships()).hasSize(6);
        assertThat(model.relationships()).allSatisfy(r -> assertThat(r.ends()).hasSize(2));
        assertThat(model.relationships().stream().map(ModelResource.RelationshipResource::kind).toList())
                .containsExactlyInAnyOrder(RelationshipKind.ASSOCIATION.name(), RelationshipKind.AGGREGATION.name(),
                        RelationshipKind.COMPOSITION.name(), RelationshipKind.GENERALIZATION.name(),
                        RelationshipKind.INTERFACE_REALIZATION.name(), RelationshipKind.DEPENDENCY.name());
        assertThat(model.views()).hasSize(1);

        assertThat(result.diagrams()).hasSize(1);
        var diagram = result.diagrams().get(0);
        assertThat(diagram.figures()).hasSize(2);
        assertThat(diagram.connectors()).hasSize(3);
        List<String> figureDuids = diagram.figures().stream()
                .map(XmiReadingResult.FigurePlacement::duid).toList();
        assertThat(diagram.connectors()).allSatisfy(connector -> {
            assertThat(connector.mode()).isEqualTo("3");
            assertThat(figureDuids).contains(connector.sourceDuid(), connector.targetDuid());
        });
        assertThat(result.losses()).noneMatch(loss -> loss.name().contains("anchors_missing"));
        assertThat(result.losses()).containsExactly(
                new XmiReadingResult.Loss("attribute_layout_bounds", 3),
                new XmiReadingResult.Loss("attribute_layout_containment", 3),
                new XmiReadingResult.Loss("attribute_layout_coords", 3),
                new XmiReadingResult.Loss("constraint_severity_not_representable", 1),
                new XmiReadingResult.Loss("unresolved_constraint_expression", 1),
                new XmiReadingResult.Loss("unresolved_relationship_end", 2));
    }

    @Test
    void namesTheDialectItCannotReadInsteadOfOnlyTheMissingElement() throws Exception {
        // Measured against what Enterprise Architect actually exports: its default export is the XMI 1.1 dialect, and
        // its XMI 2.2 export declares the UML 2.2 namespace. The reader implements 2.1 and has to say so.
        assertThatThrownBy(() -> reader.read(Files.readAllBytes(EA_DEFAULT_DIALECT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XMI 1.1")
                .hasMessageContaining("Enterprise Architect exports by default")
                .hasMessageContaining("as XMI 2.1");
        assertThatThrownBy(() -> reader.read(Files.readAllBytes(EA_UML_22)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http://schema.omg.org/spec/UML/2.2")
                .hasMessageContaining("implements http://schema.omg.org/spec/UML/2.1")
                .hasMessageContaining("as XMI 2.1");
    }

    @Test
    void everyCommittedEaArtifactIsByteExact() throws Exception {
        // The dialect is the evidence, so each Enterprise Architect artifact is pinned by hash: a fixture that changed
        // silently would turn a measurement into an opinion. `.gitattributes` keeps docs/fixtures/** byte-exact.
        assertThat(sha256(FIXTURE)).isEqualTo("336d13b1ae87da5643dc74903e9787899aaeb3896eb2ca30d5c5a74a2cd0dab2");
        assertThat(sha256(EA_INDEPENDENT)).isEqualTo("42a7c4abb8545d09fa5dc37203076b3b757e9d7e6822c089744bc0cbd9a822bc");
        assertThat(sha256(EA_ROUND_TRIP)).isEqualTo("5184207171448479880bbda4c184fcb5df1a3053c1814880a0750bc985fb718b");
        assertThat(sha256(EA_DEFAULT_DIALECT)).isEqualTo("2547bc7fa16d63600a72b350cc17d74af4a1b04272b4bb59d99ac1751902301a");
        assertThat(sha256(EA_UML_22)).isEqualTo("a97c90bc49290fe34da3e0f2405575c6bbdbdf90a1be0aeafb426900b5beaa16");
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
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
