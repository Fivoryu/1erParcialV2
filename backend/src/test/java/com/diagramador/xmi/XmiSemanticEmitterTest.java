package com.diagramador.xmi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramador.api.ModelResource;
import com.diagramador.api.ModelResource.AttributeResource;
import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.api.ModelResource.ConnectorPathResource;
import com.diagramador.api.ModelResource.ConstraintResource;
import com.diagramador.api.ModelResource.GeometryResource;
import com.diagramador.api.ModelResource.OperationResource;
import com.diagramador.api.ModelResource.ParameterResource;
import com.diagramador.api.ModelResource.RelationshipEndResource;
import com.diagramador.api.ModelResource.RelationshipResource;
import com.diagramador.api.ModelResource.ViewResource;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.TargetType;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

class XmiSemanticEmitterTest {

    private static final String XMI_NAMESPACE = "http://schema.omg.org/spec/XMI/2.1";
    private static final String UML_NAMESPACE = "http://schema.omg.org/spec/UML/2.1";

    private final XmiSemanticEmitter emitter = new XmiSemanticEmitter();

    @Test
    void emitsSemanticModelAndResolvesEveryReference() throws Exception {
        ModelResource model = sampleModel();
        XmiEmissionResult result = emitter.emit(model);
        Document document = parse(result.document());
        Element root = document.getDocumentElement();

        assertThat(result.document()).startsWith(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>");
        assertThat(root.getNamespaceURI()).isEqualTo(XMI_NAMESPACE);
        assertThat(root.getAttributeNS(XMI_NAMESPACE, "version")).isEqualTo("2.1");
        assertThat(find(document, XMI_NAMESPACE, "Documentation").getAttribute("exporter"))
                .isEqualTo("UML Class Diagrammer")
                .isNotEqualTo("Enterprise Architect");
        Element extension = find(document, XMI_NAMESPACE, "Extension");
        assertThat(extension.getAttribute("extender")).isEqualTo("Enterprise Architect");
        assertThat(childNames(extension)).containsExactly("elements", "connectors", "primitivetypes", "profiles",
                "diagrams");
        assertThat(directChildrenNoNamespace(extension, "elements")).hasSize(1);
        assertThat(directChildrenNoNamespace(extension, "elements").get(0).getChildNodes().getLength()).isZero();
        assertThat(directChildrenNoNamespace(extension, "connectors")).hasSize(1);
        List<Element> connectorRecords = directChildrenNoNamespace(
                directChildrenNoNamespace(extension, "connectors").get(0), "connector");
        assertThat(connectorRecords).hasSize(6);
        assertThat(connectorRecords).extracting(record -> record.getAttributeNS(XMI_NAMESPACE, "idref"))
                .contains(XmiIdCodec.classifierId(id(50)), XmiIdCodec.classifierId(id(60)),
                        XmiIdCodec.classifierId(id(70)), XmiIdCodec.classifierId(id(80)),
                        XmiIdCodec.classifierId(id(90)), XmiIdCodec.classifierId(id(100)));
        assertThat(connectorRecords).allSatisfy(record -> {
            assertThat(childNames(record).subList(0, 2)).containsExactly("source", "target");
            assertThat(directChildrenNoNamespace(record, "source")).hasSize(1);
            assertThat(directChildrenNoNamespace(record, "target")).hasSize(1);
        });
        Element primitiveTypes = directChildrenNoNamespace(extension, "primitivetypes").get(0);
        Element primitivePackage = directChildrenNoNamespace(primitiveTypes, "packagedElement").get(0);
        assertThat(primitivePackage.getAttributeNS(XMI_NAMESPACE, "id")).isEqualTo("EAPrimitiveTypesPackage");
        assertThat(primitivePackage.getAttribute("name")).isEqualTo("EA_PrimitiveTypes_Package");
        Element javaPackage = directChildrenNoNamespace(primitivePackage, "packagedElement").get(0);
        assertThat(javaPackage.getAttributeNS(XMI_NAMESPACE, "id")).isEqualTo("EAJavaTypesPackage");
        assertThat(javaPackage.getAttribute("name")).isEqualTo("EA_Java_Types_Package");
        assertThat(directChildrenNoNamespace(javaPackage, "packagedElement")).isEmpty();
        assertThat(directChildrenNoNamespace(extension, "profiles").get(0).getChildNodes().getLength()).isZero();
        assertThat(directChildrenNoNamespace(extension, "diagrams").get(0).getChildNodes().getLength()).isZero();

        Element umlModel = find(document, UML_NAMESPACE, "Model");
        assertThat(umlModel.getAttribute("name")).isEqualTo("EA_Model");
        assertThat(umlModel.getAttributeNS(XMI_NAMESPACE, "id"))
                .isEqualTo(XmiIdCodec.packageId(id(1)));

        Element outerPackage = byId(document, XmiIdCodec.packageId(id(10)));
        Element innerPackage = directChildById(outerPackage, XmiIdCodec.packageId(id(11)));
        Element nestedClass = directChildById(innerPackage, XmiIdCodec.classifierId(id(13)));
        assertThat(outerPackage.getAttributeNS(XMI_NAMESPACE, "type")).isEqualTo("uml:Package");
        assertThat(nestedClass.getAttribute("name")).isEqualTo("Nested");

        Element featureClass = byId(document, XmiIdCodec.classifierId(id(14)));
        Element label = byId(document, XmiIdCodec.classifierId(id(20)));
        assertThat(label.getAttribute("visibility")).isEqualTo("private");
        assertThat(findDirect(label, UML_NAMESPACE, "defaultValue").getAttribute("value")).isEqualTo("guest");
        assertThat(findDirect(label, UML_NAMESPACE, "type").getAttributeNS(XMI_NAMESPACE, "idref"))
                .isEqualTo(primitiveTypeId("String"));
        Element nickname = byId(document, XmiIdCodec.classifierId(id(21)));
        assertThat(nickname.getAttribute("visibility")).isEqualTo("package");
        assertThat(findDirect(nickname, UML_NAMESPACE, "lowerValue").getAttribute("value")).isEqualTo("0");
        Element finiteUpper = findDirect(nickname, UML_NAMESPACE, "upperValue");
        assertThat(finiteUpper.getAttribute("value")).isEqualTo("1");
        assertThat(finiteUpper.getAttributeNS(XMI_NAMESPACE, "type")).isEqualTo("uml:LiteralInteger");
        assertThat(finiteUpper.getAttributeNS(XMI_NAMESPACE, "id")).isNotEmpty();
        Element finiteLower = findDirect(nickname, UML_NAMESPACE, "lowerValue");
        assertThat(finiteLower.getAttributeNS(XMI_NAMESPACE, "type")).isEqualTo("uml:LiteralInteger");
        assertThat(finiteLower.getAttributeNS(XMI_NAMESPACE, "id")).isNotEmpty();
        assertThat(childNames(nickname)).containsExactly("lowerValue", "upperValue", "type");
        assertThat(directChildren(nickname, UML_NAMESPACE, "type").get(0).getAttributeNS(XMI_NAMESPACE, "idref"))
                .isEqualTo(primitiveTypeId("String"));
        assertThat(List.of("isStatic", "isReadOnly", "isDerived", "isOrdered", "isUnique", "isDerivedUnion"))
                .allSatisfy(flag -> assertThat(nickname.hasAttribute(flag)).isFalse());

        Element operation = byId(document, XmiIdCodec.classifierId(id(30)));
        assertThat(operation.hasAttributeNS(XMI_NAMESPACE, "type")).isFalse();
        assertThat(operation.getAttribute("concurrency")).isEqualTo("sequential");
        assertThat(operation.getAttribute("visibility")).isEqualTo("protected");
        assertThat(operation.getAttribute("isAbstract")).isEqualTo("true");
        assertThat(operation.getAttribute("isStatic")).isEqualTo("true");
        Element inputParameter = directChildren(operation, UML_NAMESPACE, "ownedParameter").stream()
                .filter(parameter -> parameter.getAttribute("name").equals("input"))
                .findFirst().orElseThrow();
        assertThat(inputParameter.getAttribute("direction")).isEqualTo("in");
        Element returnParameter = directChildren(operation, UML_NAMESPACE, "ownedParameter").stream()
                .filter(parameter -> parameter.getAttribute("direction").equals("return"))
                .findFirst().orElseThrow();
        assertThat(returnParameter.getAttribute("type")).isEqualTo(primitiveTypeId("Boolean"));
        assertThat(directChildren(returnParameter, UML_NAMESPACE, "type")).isEmpty();
        assertThat(returnParameter.getAttributeNS(XMI_NAMESPACE, "id"))
                .matches("EAID_RT000000_[A-F0-9]{4}_[A-F0-9]{4}_[A-F0-9]{4}_[A-F0-9]{12}");
        assertThat(inputParameter.getAttribute("type")).isEqualTo(primitiveTypeId("String"));

        Element enumeration = byId(document, XmiIdCodec.classifierId(id(16)));
        assertThat(enumeration.getAttributeNS(XMI_NAMESPACE, "type")).isEqualTo("uml:Enumeration");
        assertThat(directChildren(enumeration, UML_NAMESPACE, "ownedAttribute"))
                .extracting(element -> element.getAttribute("name"))
                .containsExactly("ACTIVE");
        assertThat(enumeration.getElementsByTagNameNS(UML_NAMESPACE, "ownedLiteral").getLength()).isZero();

        Element abstractClass = byId(document, XmiIdCodec.classifierId(id(17)));
        assertThat(abstractClass.getAttributeNS(XMI_NAMESPACE, "type")).isEqualTo("uml:Class");
        assertThat(abstractClass.getAttribute("isAbstract")).isEqualTo("true");

        Element association = byId(document, XmiIdCodec.classifierId(id(50)));
        assertThat(association.getAttributeNS(XMI_NAMESPACE, "type")).isEqualTo("uml:Association");
        List<Element> memberEnds = directChildren(association, UML_NAMESPACE, "memberEnd");
        List<Element> ownedEnds = directChildren(association, UML_NAMESPACE, "ownedEnd");
        assertThat(memberEnds).hasSize(2);
        assertThat(ownedEnds).hasSize(2);
        assertThat(memberEnds).extracting(member -> member.getAttributeNS(XMI_NAMESPACE, "idref"))
                .containsExactlyElementsOf(ownedEnds.stream()
                        .map(end -> end.getAttributeNS(XMI_NAMESPACE, "id")).toList());
        assertThat(memberEnds).allSatisfy(member -> assertThat(member.getAttributes().getLength()).isEqualTo(1));
        assertThat(ownedEnds).allSatisfy(end -> {
            assertThat(end.getAttribute("association")).isEqualTo(XmiIdCodec.classifierId(id(50)));
            assertThat(end.getAttribute("name")).isNotEmpty();
            assertThat(end.getAttribute("visibility")).isEqualTo("public");
            assertThat(end.getAttribute("aggregation")).isNotEmpty();
            assertThat(end.getAttributeNS(XMI_NAMESPACE, "type")).isEqualTo("uml:Property");
            assertThat(childNames(end)).containsExactly("type", "lowerValue", "upperValue");
        });
        Element manyEnd = ownedEnds.stream()
                .filter(end -> end.getAttribute("name").equals("items"))
                .findFirst().orElseThrow();
        assertThat(findDirect(manyEnd, UML_NAMESPACE, "lowerValue").getAttribute("value")).isEqualTo("0");
        Element upper = findDirect(manyEnd, UML_NAMESPACE, "upperValue");
        assertThat(upper.getAttribute("value")).isEqualTo("-1");
        assertThat(upper.getAttributeNS(XMI_NAMESPACE, "type")).isEqualTo("uml:LiteralUnlimitedNatural");
        assertThat(upper.getAttributeNS(XMI_NAMESPACE, "id")).isNotEmpty();
        assertThat(allElements(document).stream()
                .filter(element -> Set.of("lowerValue", "upperValue").contains(element.getLocalName()))
                .map(element -> element.getAttribute("value")))
                .doesNotContain("*");

        Element generalization = find(document, UML_NAMESPACE, "generalization");
        assertThat(generalization.getAttributeNS(XMI_NAMESPACE, "id"))
                .isEqualTo(XmiIdCodec.classifierId(id(60)));
        assertThat(generalization.getAttributeNS(XMI_NAMESPACE, "type")).isEqualTo("uml:Generalization");
        assertThat(generalization.getAttribute("general")).isEqualTo(XmiIdCodec.classifierId(id(12)));
        assertThat(generalization.getParentNode()).isEqualTo(nestedClass);
        assertThat(directChildren(umlModel, UML_NAMESPACE, "packagedElement"))
                .noneMatch(element -> element.getAttributeNS(XMI_NAMESPACE, "id")
                        .equals(XmiIdCodec.classifierId(id(60))));

        Element realization = byId(document, XmiIdCodec.classifierId(id(70)));
        assertThat(realization.getParentNode()).isEqualTo(umlModel);
        assertThat(directChildren(umlModel, UML_NAMESPACE, "packagedElement")).contains(realization);
        assertThat(realization.getAttributeNS(XMI_NAMESPACE, "type")).isEqualTo("uml:Realization");
        assertThat(realization.getAttribute("name")).isEqualTo("implements");
        assertThat(realization.getAttribute("visibility")).isEqualTo("public");
        assertThat(realization.getAttribute("client")).isEqualTo(XmiIdCodec.classifierId(id(14)));
        assertThat(realization.getAttribute("supplier")).isEqualTo(XmiIdCodec.classifierId(id(15)));
        Element dependency = byId(document, XmiIdCodec.classifierId(id(80)));
        assertThat(dependency.getAttributeNS(XMI_NAMESPACE, "type")).isEqualTo("uml:Dependency");
        Element aggregation = byId(document, XmiIdCodec.classifierId(id(90)));
        assertThat(aggregation.getAttributeNS(XMI_NAMESPACE, "type")).isEqualTo("uml:Association");
        assertThat(directChildren(aggregation, UML_NAMESPACE, "ownedEnd"))
                .anySatisfy(end -> assertThat(end.getAttribute("aggregation")).isEqualTo("shared"));
        Element composition = byId(document, XmiIdCodec.classifierId(id(100)));
        assertThat(directChildren(composition, UML_NAMESPACE, "ownedEnd"))
                .anySatisfy(end -> assertThat(end.getAttribute("aggregation")).isEqualTo("composite"));
        Element oneOrMany = directChildren(aggregation, UML_NAMESPACE, "ownedEnd").stream()
                .filter(end -> end.getAttribute("name").equals("parts"))
                .findFirst().orElseThrow();
        assertThat(findDirect(oneOrMany, UML_NAMESPACE, "lowerValue").getAttribute("value")).isEqualTo("1");
        Element unboundedUpper = findDirect(oneOrMany, UML_NAMESPACE, "upperValue");
        assertThat(unboundedUpper.getAttribute("value")).isEqualTo("-1");
        assertThat(unboundedUpper.getAttributeNS(XMI_NAMESPACE, "type"))
                .isEqualTo("uml:LiteralUnlimitedNatural");

        Element constraint = find(document, UML_NAMESPACE, "ownedRule");
        assertThat(findDirect(findDirect(constraint, UML_NAMESPACE, "specification"), UML_NAMESPACE, "body")
                .getTextContent()).isEqualTo("label <> ''");
        assertThat(result.losses()).containsExactly(
                new XmiEmissionResult.Loss("constraint_severity", 1),
                new XmiEmissionResult.Loss("extension_connector_vendor_metadata", 6),
                new XmiEmissionResult.Loss("extension_primitive_type_catalog", 2));
        assertAllReferencesResolve(document);
    }

    @Test
    void emitsMeasuredExtensionElementAndAttributeLayoutWithoutInventingGeometry() throws Exception {
        ModelResource model = sampleModel();
        ViewResource defaultView = new ViewResource(id(300), "Default", true,
                List.of(new GeometryResource(id(301), TargetType.CLASSIFIER, id(13), 50, 50, 200, 90, 1),
                        new GeometryResource(id(302), TargetType.CLASSIFIER, id(14), 350, 50, 200, 90, 2)),
                List.of(new ConnectorPathResource(id(303), id(50), 0, 10, 20),
                        new ConnectorPathResource(id(304), id(50), 1, 30, 40)));
        ModelResource placedModel = new ModelResource(model.id(), model.projectId(), model.revision(), model.versionId(),
                List.of(defaultView), model.classifiers(), model.relationships());

        XmiEmissionResult result = emitter.emit(placedModel);
        Document document = parse(result.document());
        Element extension = find(document, XMI_NAMESPACE, "Extension");
        Element elements = directChildrenNoNamespace(extension, "elements").get(0);
        List<Element> records = directChildrenNoNamespace(elements, "element");

        assertThat(records).hasSize(4);
        assertThat(records).extracting(record -> record.getAttribute("name"))
                .containsExactly("Outer", "Feature", "Inner", "Nested");

        Element diagram = directChildrenNoNamespace(extension, "diagrams").get(0);
        List<Element> diagrams = directChildrenNoNamespace(diagram, "diagram");
        assertThat(diagrams).hasSize(1);
        Element emittedDiagram = diagrams.get(0);
        assertThat(childNames(emittedDiagram)).containsExactly("model", "properties", "project", "style1", "style2",
                "swimlanes", "matrixitems", "extendedProperties", "elements");
        assertThat(findDirectNoNamespace(emittedDiagram, "model").getAttribute("package"))
                .isEqualTo(XmiIdCodec.packageId(model.id()));
        Element diagramProperties = findDirectNoNamespace(emittedDiagram, "properties");
        assertThat(diagramProperties.getAttribute("name")).isEqualTo("Default");
        assertThat(diagramProperties.hasAttribute("type")).isFalse();
        assertThat(diagramProperties.hasAttribute("documentation")).isFalse();

        Element diagramElements = findDirectNoNamespace(emittedDiagram, "elements");
        List<Element> placements = directChildrenNoNamespace(diagramElements, "element");
        List<Element> figurePlacements = placements.stream()
                .filter(placement -> placement.getAttribute("geometry").startsWith("Left="))
                .toList();
        List<Element> connectorPlacements = placements.stream()
                .filter(placement -> placement.getAttribute("geometry").startsWith("SX="))
                .toList();
        assertThat(figurePlacements).hasSize(2);
        assertThat(figurePlacements).extracting(placement -> placement.getAttribute("geometry"))
                .containsExactly("Left=50;Top=50;Right=250;Bottom=140;",
                        "Left=350;Top=50;Right=550;Bottom=140;");
        assertThat(connectorPlacements).hasSize(3);
        Element nestedFigure = figurePlacements.stream()
                .filter(placement -> placement.getAttribute("subject").equals(XmiIdCodec.classifierId(id(13))))
                .findFirst().orElseThrow();
        assertThat(nestedFigure.getAttribute("geometry"))
                .isEqualTo("Left=50;Top=50;Right=250;Bottom=140;");
        assertThat(nestedFigure.getAttribute("seqno")).isEqualTo("1");
        assertThat(nestedFigure.getAttribute("style")).matches("DUID=[0-9A-F]{8};");
        assertThat(XmiIdCodec.classifierUuid(nestedFigure.getAttribute("subject"))).isEqualTo(id(13));
        assertThat(figurePlacements).allSatisfy(placement ->
                assertThat(placement.getAttribute("geometry"))
                        .matches("Left=-?\\d+;Top=-?\\d+;Right=-?\\d+;Bottom=-?\\d+;"));

        java.util.Map<String, String> duidsBySubject = new java.util.HashMap<>();
        for (Element figure : figurePlacements) {
            String duid = styleValue(figure, "DUID");
            assertThat(duidsBySubject.put(figure.getAttribute("subject"), duid)).isNull();
        }
        assertThat(duidsBySubject.values()).doesNotHaveDuplicates();

        assertThat(connectorPlacements).allSatisfy(placement -> {
            assertThat(placement.getAttribute("geometry")).isEqualTo("SX=0;SY=0;EX=0;EY=0;Path=;");
            assertThat(placement.hasAttribute("seqno")).isFalse();
            assertThat(placement.getAttribute("style"))
                    .matches("Mode=3;EOID=[0-9A-F]{8};SOID=[0-9A-F]{8};Color=-1;LWidth=0;Hidden=0;");
        });
        Element associationPlacement = connectorPlacements.stream()
                .filter(placement -> placement.getAttribute("subject").equals(XmiIdCodec.classifierId(id(50))))
                .findFirst().orElseThrow();
        assertThat(styleValue(associationPlacement, "SOID"))
                .isEqualTo(duidsBySubject.get(XmiIdCodec.classifierId(id(14))));
        assertThat(styleValue(associationPlacement, "EOID"))
                .isEqualTo(duidsBySubject.get(XmiIdCodec.classifierId(id(13))));

        Element connectorRecords = directChildrenNoNamespace(extension, "connectors").get(0);
        Element associationRecord = extensionConnectorByIdref(connectorRecords, XmiIdCodec.classifierId(id(50)));
        assertThat(childNames(associationRecord)).containsExactly("source", "target", "model", "properties",
                "modifiers", "appearance", "labels", "style");
        assertThat(findDirectNoNamespace(associationRecord, "source").getAttributeNS(XMI_NAMESPACE, "idref"))
                .isEqualTo(XmiIdCodec.classifierId(id(14)));
        assertThat(findDirectNoNamespace(associationRecord, "target").getAttributeNS(XMI_NAMESPACE, "idref"))
                .isEqualTo(XmiIdCodec.classifierId(id(13)));
        Element associationLabels = findDirectNoNamespace(associationRecord, "labels");
        assertThat(associationLabels.getAttribute("lb")).isEqualTo("1");
        assertThat(associationLabels.getAttribute("lt")).isEqualTo("+owner");
        assertThat(associationLabels.getAttribute("rb")).isEqualTo("0..*");
        assertThat(associationLabels.getAttribute("rt")).isEqualTo("+items");
        assertThat(associationLabels.getAttribute("mt")).isEqualTo("contains");
        assertThat(findDirectNoNamespace(associationRecord, "properties").getAttribute("ea_type"))
                .isEqualTo("Association");
        assertAllReferencesResolve(document);
        Element outerPackage = extensionElementByIdref(elements, XmiIdCodec.packageId(id(10)));
        Element innerPackage = extensionElementByIdref(elements, XmiIdCodec.packageId(id(11)));
        Element nested = extensionElementByIdref(elements, XmiIdCodec.classifierId(id(13)));
        Element feature = extensionElementByIdref(elements, XmiIdCodec.classifierId(id(14)));
        assertThat(outerPackage.getAttributeNS(XMI_NAMESPACE, "type")).isEqualTo("uml:Package");
        assertThat(outerPackage.getAttribute("name")).isEqualTo("Outer");
        assertThat(innerPackage.getAttribute("name")).isEqualTo("Inner");
        assertThat(findDirectNoNamespace(innerPackage, "model").getAttribute("package"))
                .isEqualTo(XmiIdCodec.packageId(id(10)));
        assertThat(findDirectNoNamespace(nested, "model").getAttribute("package"))
                .isEqualTo(XmiIdCodec.packageId(id(11)));
        assertThat(feature.getAttributeNS(XMI_NAMESPACE, "type")).isEqualTo("uml:Class");
        assertThat(feature.hasAttribute("scope")).isFalse();
        assertThat(findDirectNoNamespace(feature, "properties").getAttribute("sType")).isEqualTo("Class");
        assertThat(findDirectNoNamespace(feature, "properties").getAttribute("isAbstract")).isEqualTo("false");

        List<Element> attributes = directChildrenNoNamespace(findDirectNoNamespace(feature, "attributes"),
                "attribute");
        assertThat(attributes).extracting(attribute -> attribute.getAttribute("name"))
                .containsExactly("label", "nickname");
        assertThat(attributes.get(0).getAttributeNS(XMI_NAMESPACE, "idref"))
                .isEqualTo(XmiIdCodec.classifierId(id(20)));
        assertThat(attributes.get(0).getAttribute("scope")).isEqualTo("Private");
        assertThat(childNames(attributes.get(0))).containsExactly("coords", "containment", "bounds");
        Element coords = findDirectNoNamespace(attributes.get(0), "coords");
        assertThat(coords.getAttribute("ordered")).isEqualTo("0");
        Element containment = findDirectNoNamespace(attributes.get(0), "containment");
        assertThat(containment.getAttribute("position")).isEqualTo("0");
        assertThat(containment.hasAttribute("containment")).isFalse();
        Element bounds = findDirectNoNamespace(attributes.get(0), "bounds");
        assertThat(bounds.getAttribute("lower")).isEqualTo("1");
        assertThat(bounds.getAttribute("upper")).isEqualTo("1");
        assertThat(childNames(attributes.get(1))).containsExactly("coords", "containment");
        assertThat(findDirectNoNamespace(attributes.get(1), "containment").getAttribute("position"))
                .isEqualTo("1");
        ViewResource reversedView = new ViewResource(defaultView.id(), defaultView.name(), true,
                List.of(defaultView.elementGeometry().get(1), defaultView.elementGeometry().get(0)), List.of());
        ModelResource reorderedModel = reordered(placedModel);
        ModelResource reorderedPlacements = new ModelResource(reorderedModel.id(), reorderedModel.projectId(),
                reorderedModel.revision(), reorderedModel.versionId(), List.of(reversedView), reorderedModel.classifiers(),
                reorderedModel.relationships());
        assertThat(emitter.emit(reorderedPlacements).document()).isEqualTo(result.document());

        assertThat(result.losses()).containsExactly(
                new XmiEmissionResult.Loss("attribute_layout_bounds", 1),
                new XmiEmissionResult.Loss("attribute_layout_containment_kind", 2),
                new XmiEmissionResult.Loss("attribute_layout_coordinates", 2),
                new XmiEmissionResult.Loss("attribute_layout_position_approximation", 2),
                new XmiEmissionResult.Loss("constraint_severity", 1),
                new XmiEmissionResult.Loss("extension_connector_vendor_metadata", 6),
                new XmiEmissionResult.Loss("extension_connector_waypoints", 2),
                new XmiEmissionResult.Loss("extension_diagram_matrixitems_metadata", 1),
                new XmiEmissionResult.Loss("extension_diagram_model_metadata", 1),
                new XmiEmissionResult.Loss("extension_diagram_project_metadata", 1),
                new XmiEmissionResult.Loss("extension_diagram_properties_metadata", 1),
                new XmiEmissionResult.Loss("extension_diagram_style1_metadata", 1),
                new XmiEmissionResult.Loss("extension_diagram_style2_metadata", 1),
                new XmiEmissionResult.Loss("extension_diagram_swimlanes_metadata", 1),
                new XmiEmissionResult.Loss("extension_element_vendor_metadata", 4),
                new XmiEmissionResult.Loss("extension_primitive_type_catalog", 2));
        assertAllReferencesResolve(document);
    }

    @Test
    void packageRelationshipEndpointsResolveAndNavigabilityLossIsReported() throws Exception {
        ClassifierResource container = new ClassifierResource(id(400), "Container", ClassifierKind.PACKAGE, false,
                null, List.of(), List.of(), List.of());
        AttributeResource wildcard = new AttributeResource(id(411), "wildcard", "ATTRIBUTE", "PUBLIC",
                MultiplicityKind.EXACTLY_ONE, "*", id(412), "String", 0);
        ClassifierResource child = new ClassifierResource(id(401), "Child", ClassifierKind.CLASS, false,
                id(400), List.of(wildcard), List.of(), List.of());
        RelationshipResource association = new RelationshipResource(id(420), "ASSOCIATION", "association",
                List.of(new RelationshipEndResource(id(421), id(401), "child", MultiplicityKind.EXACTLY_ONE,
                                NavigationKind.NAVIGABLE, "none", (short) 1),
                        new RelationshipEndResource(id(422), id(400), "container", MultiplicityKind.EXACTLY_ONE,
                                NavigationKind.NOT_NAVIGABLE, "none", (short) 2)), List.of());
        RelationshipResource generalization = new RelationshipResource(id(440), "GENERALIZATION", null,
                List.of(end(id(441), id(401), null, MultiplicityKind.EXACTLY_ONE, "none", (short) 1),
                        end(id(442), id(400), null, MultiplicityKind.EXACTLY_ONE, "none", (short) 2)), List.of());
        RelationshipResource dependency = new RelationshipResource(id(430), "DEPENDENCY", "uses",
                List.of(end(id(431), id(401), null, MultiplicityKind.EXACTLY_ONE, "none", (short) 1),
                        end(id(432), id(400), null, MultiplicityKind.EXACTLY_ONE, "none", (short) 2)), List.of());
        ModelResource model = new ModelResource(id(1), id(2), 3, id(3), List.of(), List.of(container, child),
                List.of(association, generalization, dependency));

        XmiEmissionResult result = emitter.emit(model);
        Document document = parse(result.document());
        String packageId = XmiIdCodec.packageId(id(400));

        Element packageElement = byId(document, packageId);
        Element emittedAssociation = byId(document, XmiIdCodec.classifierId(id(420)));
        Element packageEnd = directChildren(emittedAssociation, UML_NAMESPACE, "ownedEnd").stream()
                .filter(end -> end.getAttribute("name").equals("container"))
                .findFirst().orElseThrow();
        assertThat(findDirect(packageEnd, UML_NAMESPACE, "type").getAttributeNS(XMI_NAMESPACE, "idref"))
                .isEqualTo(packageId);
        Element emittedGeneralization = find(document, UML_NAMESPACE, "generalization");
        assertThat(emittedGeneralization.getAttribute("general")).isEqualTo(packageId);
        Element emittedDependency = byId(document, XmiIdCodec.classifierId(id(430)));
        assertThat(emittedDependency.getAttribute("client")).isEqualTo(XmiIdCodec.classifierId(id(401)));
        assertThat(emittedDependency.getAttribute("supplier")).isEqualTo(packageId);
        assertThat(packageElement.getAttributeNS(XMI_NAMESPACE, "type")).isEqualTo("uml:Package");

        Element wildcardDefault = findDirect(byId(document, XmiIdCodec.classifierId(id(411))),
                UML_NAMESPACE, "defaultValue");
        assertThat(wildcardDefault.getAttribute("value")).isEqualTo("*");
        assertThat(result.losses()).containsExactly(
                new XmiEmissionResult.Loss("extension_connector_vendor_metadata", 3),
                new XmiEmissionResult.Loss("extension_primitive_type_catalog", 1),
                new XmiEmissionResult.Loss("unsupported_relationship_navigability", 2));
        assertAllReferencesResolve(document);
    }

    @Test
    void outputIsByteDeterministicAcrossEquivalentCollectionOrders() {
        ModelResource model = sampleModel();
        String first = emitter.emit(model).document();
        String second = emitter.emit(model).document();

        assertThat(second).isEqualTo(first);
        assertThat(emitter.emit(reordered(model)).document()).isEqualTo(first);
    }

    @Test
    void unresolvedReturnTypeUsesEmptyTypeElementAndReportsLoss() throws Exception {
        ParameterResource unresolvedReturn = new ParameterResource(id(202), "return", "RETURN", id(203), null, 0);
        OperationResource operation = new OperationResource(id(201), "unresolved", "PUBLIC", false, false,
                null, null, 0, List.of(unresolvedReturn));
        ClassifierResource owner = new ClassifierResource(id(200), "Owner", ClassifierKind.CLASS, false, null,
                List.of(), List.of(operation), List.of());
        ModelResource model = new ModelResource(id(1), id(2), 3, id(3), List.of(), List.of(owner), List.of());

        XmiEmissionResult result = emitter.emit(model);
        Document document = parse(result.document());
        Element parameter = byId(document, XmiIdCodec.returnParameterId(id(202)));

        assertThat(result.losses()).containsExactly(new XmiEmissionResult.Loss("unresolved_type_reference", 1));
        assertThat(parameter.hasAttribute("type")).isFalse();
        assertThat(directChildren(parameter, UML_NAMESPACE, "type")).hasSize(1);
        assertThat(findDirect(parameter, UML_NAMESPACE, "type").hasAttributeNS(XMI_NAMESPACE, "idref")).isFalse();
        assertAllReferencesResolve(document);
    }

    @Test
    void reportsUnresolvedTypesMissingEndsAndUnrepresentableBoundsWithoutDanglingReferences() throws Exception {
        UUID missingClassifier = id(999);
        ClassifierResource broken = new ClassifierResource(id(100), "Broken", ClassifierKind.CLASS, false, null,
                List.of(new AttributeResource(id(101), "unknown", "UNKNOWN_KIND", "PUBLIC", MultiplicityKind.RANGE,
                        null, id(102), null, 0)), List.of(),
                List.of(new ConstraintResource(id(110), null, null)));
        ClassifierResource orphan = new ClassifierResource(id(103), "Orphan", ClassifierKind.CLASS, false, id(104),
                List.of(), List.of(), List.of());
        RelationshipResource missingEnd = new RelationshipResource(id(105), "ASSOCIATION", "missing",
                List.of(end(id(106), id(100), "only", MultiplicityKind.EXACTLY_ONE, "none", (short) 1)), List.of());
        RelationshipResource unresolvedEnd = new RelationshipResource(id(107), "ASSOCIATION", "unresolved",
                List.of(end(id(108), id(100), "valid", MultiplicityKind.EXACTLY_ONE, "none", (short) 1),
                        end(id(109), missingClassifier, "missing", MultiplicityKind.EXACTLY_ONE, "none", (short) 2)),
                List.of());
        ModelResource model = new ModelResource(id(1), id(2), 3, id(3), List.of(), List.of(broken, orphan),
                List.of(missingEnd, unresolvedEnd));

        XmiEmissionResult result = emitter.emit(model);
        Document document = parse(result.document());

        assertThat(result.losses()).containsExactly(
                new XmiEmissionResult.Loss("relationship_missing_ends", 1),
                new XmiEmissionResult.Loss("relationship_unresolved_end", 1),
                new XmiEmissionResult.Loss("unresolved_constraint_expression", 1),
                new XmiEmissionResult.Loss("unresolved_namespace", 1),
                new XmiEmissionResult.Loss("unresolved_type_reference", 1),
                new XmiEmissionResult.Loss("unsupported_attribute_kind", 1),
                new XmiEmissionResult.Loss("unsupported_multiplicity_range", 1));
        Element attribute = byId(document, XmiIdCodec.classifierId(id(101)));
        assertThat(attribute.getElementsByTagNameNS(UML_NAMESPACE, "type").getLength()).isZero();
        assertThat(attribute.getElementsByTagNameNS(UML_NAMESPACE, "lowerValue").getLength()).isZero();
        assertThat(allElements(document).stream()
                .filter(element -> element.getAttributeNS(XMI_NAMESPACE, "type").equals("uml:Association")))
                .isEmpty();
        assertAllReferencesResolve(document);
    }

    @Test
    void classifierAndPackageIdentifiersRoundTripExactly() {
        UUID canonicalId = UUID.fromString("6e23cbf8-6443-44b7-9df1-15a32ad4690c");

        String classifierId = XmiIdCodec.classifierId(canonicalId);
        String packageId = XmiIdCodec.packageId(canonicalId);

        assertThat(classifierId).isEqualTo("EAID_6E23CBF8_6443_44B7_9DF1_15A32AD4690C");
        assertThat(packageId).isEqualTo("EAPK_6E23CBF8_6443_44B7_9DF1_15A32AD4690C");
        assertThat(XmiIdCodec.classifierUuid(classifierId)).isEqualTo(canonicalId);
        assertThat(XmiIdCodec.packageUuid(packageId)).isEqualTo(canonicalId);
        assertThatThrownBy(() -> XmiIdCodec.classifierUuid("EAID_not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ModelResource sampleModel() {
        ClassifierResource outerPackage = new ClassifierResource(id(10), "Outer", ClassifierKind.PACKAGE, false,
                null, List.of(), List.of(), List.of());
        ClassifierResource innerPackage = new ClassifierResource(id(11), "Inner", ClassifierKind.PACKAGE, false,
                id(10), List.of(), List.of(), List.of());
        ClassifierResource base = new ClassifierResource(id(12), "Base", ClassifierKind.CLASS, false, null,
                List.of(), List.of(), List.of());
        ClassifierResource nested = new ClassifierResource(id(13), "Nested", ClassifierKind.CLASS, false, id(11),
                List.of(), List.of(), List.of());
        AttributeResource label = new AttributeResource(id(20), "label", "ATTRIBUTE", "PRIVATE",
                MultiplicityKind.EXACTLY_ONE, "guest", id(120), "String", 0);
        AttributeResource nickname = new AttributeResource(id(21), "nickname", "ATTRIBUTE", "PACKAGE",
                MultiplicityKind.ZERO_OR_ONE, null, id(123), "String", 1);
        ParameterResource input = new ParameterResource(id(31), "input", "IN", id(122), "String", 0);
        OperationResource operation = new OperationResource(id(30), "transform", "PROTECTED", true, true,
                id(121), "Boolean", 0, List.of(input));
        ClassifierResource feature = new ClassifierResource(id(14), "Feature", ClassifierKind.CLASS, false, null,
                List.of(label, nickname), List.of(operation),
                List.of(new ConstraintResource(id(32), "label <> ''", "WARNING")));
        ClassifierResource contract = new ClassifierResource(id(15), "Contract", ClassifierKind.INTERFACE, true, null,
                List.of(), List.of(), List.of());
        AttributeResource constant = new AttributeResource(id(40), "ACTIVE", "LITERAL", "PUBLIC",
                MultiplicityKind.EXACTLY_ONE, null, null, null, 0);
        ClassifierResource enumeration = new ClassifierResource(id(16), "Status", ClassifierKind.ENUMERATION, false,
                null, List.of(constant), List.of(), List.of());
        ClassifierResource abstractClass = new ClassifierResource(id(17), "AbstractBase",
                ClassifierKind.ABSTRACT_CLASS, true, null, List.of(), List.of(), List.of());

        RelationshipResource association = new RelationshipResource(id(50), "ASSOCIATION", "contains",
                List.of(end(id(51), id(14), "owner", MultiplicityKind.EXACTLY_ONE, "none", (short) 1),
                        end(id(52), id(13), "items", MultiplicityKind.ZERO_OR_MANY, "none", (short) 2)), List.of());
        RelationshipResource generalization = new RelationshipResource(id(60), "GENERALIZATION", null,
                List.of(end(id(61), id(13), null, MultiplicityKind.EXACTLY_ONE, "none", (short) 1),
                        end(id(62), id(12), null, MultiplicityKind.EXACTLY_ONE, "none", (short) 2)), List.of());
        RelationshipResource realization = new RelationshipResource(id(70), "INTERFACE_REALIZATION", "implements",
                List.of(end(id(71), id(14), null, MultiplicityKind.EXACTLY_ONE, "none", (short) 1),
                        end(id(72), id(15), null, MultiplicityKind.EXACTLY_ONE, "none", (short) 2)), List.of());
        RelationshipResource dependency = new RelationshipResource(id(80), "DEPENDENCY", "uses",
                List.of(end(id(81), id(14), null, MultiplicityKind.EXACTLY_ONE, "none", (short) 1),
                        end(id(82), id(15), null, MultiplicityKind.EXACTLY_ONE, "none", (short) 2)), List.of());
        RelationshipResource aggregation = new RelationshipResource(id(90), "AGGREGATION", "aggregates",
                List.of(end(id(91), id(14), "whole", MultiplicityKind.EXACTLY_ONE, "shared", (short) 1),
                        end(id(92), id(13), "parts", MultiplicityKind.ONE_OR_MANY, "none", (short) 2)), List.of());
        RelationshipResource composition = new RelationshipResource(id(100), "COMPOSITION", "owns",
                List.of(end(id(101), id(14), "container", MultiplicityKind.EXACTLY_ONE, "composite", (short) 1),
                        end(id(102), id(13), "part", MultiplicityKind.EXACTLY_ONE, "none", (short) 2)), List.of());

        return new ModelResource(id(1), id(2), 3, id(3), List.of(),
                List.of(enumeration, nested, feature, outerPackage, abstractClass, base, innerPackage, contract),
                List.of(dependency, association, realization, generalization, aggregation, composition));
    }

    private static ModelResource reordered(ModelResource model) {
        List<ClassifierResource> classifiers = new ArrayList<>();
        for (ClassifierResource classifier : model.classifiers()) {
            List<AttributeResource> attributes = new ArrayList<>(classifier.attributes());
            List<OperationResource> operations = new ArrayList<>();
            for (OperationResource operation : classifier.operations()) {
                List<ParameterResource> parameters = new ArrayList<>(operation.parameters());
                Collections.reverse(parameters);
                operations.add(new OperationResource(operation.id(), operation.name(), operation.visibility(),
                        operation.isAbstract(), operation.isStatic(), operation.returnTypeReferenceId(),
                        operation.returnType(), operation.ordinal(), parameters));
            }
            Collections.reverse(attributes);
            Collections.reverse(operations);
            List<ConstraintResource> constraints = new ArrayList<>(classifier.constraints());
            Collections.reverse(constraints);
            classifiers.add(new ClassifierResource(classifier.id(), classifier.name(), classifier.kind(),
                    classifier.isAbstract(), classifier.namespaceId(), attributes, operations, constraints));
        }
        Collections.reverse(classifiers);

        List<RelationshipResource> relationships = new ArrayList<>();
        for (RelationshipResource relationship : model.relationships()) {
            List<RelationshipEndResource> ends = new ArrayList<>(relationship.ends());
            Collections.reverse(ends);
            List<ConstraintResource> constraints = new ArrayList<>(relationship.constraints());
            Collections.reverse(constraints);
            relationships.add(new RelationshipResource(relationship.id(), relationship.kind(), relationship.name(),
                    ends, constraints));
        }
        Collections.reverse(relationships);
        return new ModelResource(model.id(), model.projectId(), model.revision(), model.versionId(), model.views(),
                classifiers, relationships);
    }

    private static RelationshipEndResource end(UUID id, UUID classifierId, String role,
            MultiplicityKind multiplicity, String aggregation, short ordinal) {
        return new RelationshipEndResource(id, classifierId, role, multiplicity, NavigationKind.UNSPECIFIED,
                aggregation, ordinal);
    }

    private static UUID id(long value) {
        return UUID.fromString("00000000-0000-0000-0000-%012x".formatted(value));
    }

    private static String primitiveTypeId(String name) {
        UUID primitiveUuid = UUID.nameUUIDFromBytes(
                ("uml-diagrammer:xmi-primitive:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return XmiIdCodec.classifierId(primitiveUuid);
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static Element find(Document document, String namespace, String localName) {
        Element element = findOptional(document, namespace, localName);
        if (element == null) {
            throw new AssertionError("Missing element " + localName);
        }
        return element;
    }

    private static Element findOptional(Document document, String namespace, String localName) {
        NodeList elements = document.getElementsByTagNameNS(namespace, localName);
        return elements.getLength() == 0 ? null : (Element) elements.item(0);
    }

    private static Element byId(Document document, String id) {
        for (Element element : allElements(document)) {
            if (id.equals(element.getAttributeNS(XMI_NAMESPACE, "id"))) {
                return element;
            }
        }
        throw new AssertionError("Missing XMI id " + id);
    }

    private static Element directChildById(Element parent, String id) {
        for (Element child : directChildren(parent, UML_NAMESPACE, "packagedElement")) {
            if (id.equals(child.getAttributeNS(XMI_NAMESPACE, "id"))) {
                return child;
            }
        }
        throw new AssertionError("Missing direct packagedElement " + id);
    }

    private static Element findDirect(Element parent, String namespace, String localName) {
        return directChildren(parent, namespace, localName).stream().findFirst()
                .orElseThrow(() -> new AssertionError("Missing child " + localName));
    }

    private static Element findDirectNoNamespace(Element parent, String localName) {
        return directChildrenNoNamespace(parent, localName).stream().findFirst()
                .orElseThrow(() -> new AssertionError("Missing child " + localName));
    }

    private static Element extensionElementByIdref(Element elements, String idref) {
        return directChildrenNoNamespace(elements, "element").stream()
                .filter(element -> element.getAttributeNS(XMI_NAMESPACE, "idref").equals(idref))
                .findFirst().orElseThrow(() -> new AssertionError("Missing extension element " + idref));
    }

    private static Element extensionConnectorByIdref(Element connectors, String idref) {
        return directChildrenNoNamespace(connectors, "connector").stream()
                .filter(element -> element.getAttributeNS(XMI_NAMESPACE, "idref").equals(idref))
                .findFirst().orElseThrow(() -> new AssertionError("Missing extension connector " + idref));
    }

    private static String styleValue(Element placement, String key) {
        for (String part : placement.getAttribute("style").split(";")) {
            int separator = part.indexOf('=');
            if (separator > 0 && part.substring(0, separator).equals(key)) {
                return part.substring(separator + 1);
            }
        }
        return "";
    }

    private static List<String> childNames(Element parent) {
        List<String> names = new ArrayList<>();
        for (int index = 0; index < parent.getChildNodes().getLength(); index++) {
            if (parent.getChildNodes().item(index) instanceof Element child) {
                names.add(child.getLocalName());
            }
        }
        return names;
    }

    private static List<Element> directChildrenNoNamespace(Element parent, String localName) {
        List<Element> children = new ArrayList<>();
        for (int index = 0; index < parent.getChildNodes().getLength(); index++) {
            if (parent.getChildNodes().item(index) instanceof Element child
                    && child.getNamespaceURI() == null
                    && localName.equals(child.getLocalName())) {
                children.add(child);
            }
        }
        return children;
    }

    private static List<Element> directChildren(Element parent, String namespace, String localName) {
        List<Element> children = new ArrayList<>();
        for (int index = 0; index < parent.getChildNodes().getLength(); index++) {
            if (parent.getChildNodes().item(index) instanceof Element child
                    && namespace.equals(child.getNamespaceURI())
                    && localName.equals(child.getLocalName())) {
                children.add(child);
            }
        }
        return children;
    }

    private static List<Element> allElements(Document document) {
        List<Element> elements = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int index = 0; index < nodes.getLength(); index++) {
            elements.add((Element) nodes.item(index));
        }
        return elements;
    }

    private static void assertAllReferencesResolve(Document document) {
        Set<String> ids = new java.util.HashSet<>();
        for (Element element : allElements(document)) {
            String id = element.getAttributeNS(XMI_NAMESPACE, "id");
            if (!id.isEmpty()) {
                assertThat(ids.add(id)).as("unique xmi:id %s", id).isTrue();
            }
        }
        for (Element element : allElements(document)) {
            String idref = element.getAttributeNS(XMI_NAMESPACE, "idref");
            if (!idref.isEmpty()) {
                assertThat(ids).as("resolved xmi:idref %s", idref).contains(idref);
            }
            String subject = element.getAttribute("subject");
            if (!subject.isEmpty()) {
                assertThat(ids).as("resolved diagram subject %s", subject).contains(subject);
            }
            for (String attribute : List.of("general", "client", "supplier", "association", "package")) {
                String reference = element.getAttribute(attribute);
                if (!reference.isEmpty()) {
                    assertThat(ids).as("resolved %s reference %s", attribute, reference).contains(reference);
                }
            }
            if ("ownedParameter".equals(element.getLocalName()) && element.hasAttribute("type")) {
                assertThat(ids).as("resolved ownedParameter type %s", element.getAttribute("type"))
                        .contains(element.getAttribute("type"));
            }
        }
        assertDiagramAnchorsResolve(document, ids);
    }

    private static void assertDiagramAnchorsResolve(Document document, Set<String> ids) {
        for (Element diagram : allElements(document).stream()
                .filter(element -> "diagram".equals(element.getLocalName())).toList()) {
            Element elements = directChildrenNoNamespace(diagram, "elements").stream()
                    .findFirst().orElseThrow(() -> new AssertionError("Missing diagram elements"));
            java.util.Map<String, String> subjectsByDuid = new java.util.HashMap<>();
            List<Element> placements = directChildrenNoNamespace(elements, "element");
            for (Element placement : placements) {
                if (!placement.getAttribute("geometry").startsWith("Left=")) {
                    continue;
                }
                String duid = styleValue(placement, "DUID");
                String subject = placement.getAttribute("subject");
                assertThat(duid).as("figure DUID").matches("[0-9A-F]{8}");
                assertThat(ids).as("resolved figure subject %s", subject).contains(subject);
                assertThat(subjectsByDuid.put(duid, subject)).as("unique DUID %s", duid).isNull();
            }
            for (Element placement : placements) {
                if (!placement.getAttribute("geometry").startsWith("SX=")) {
                    continue;
                }
                assertThat(subjectsByDuid).as("source anchor in this diagram")
                        .containsKey(styleValue(placement, "SOID"));
                assertThat(subjectsByDuid).as("target anchor in this diagram")
                        .containsKey(styleValue(placement, "EOID"));
            }
        }
    }
}
