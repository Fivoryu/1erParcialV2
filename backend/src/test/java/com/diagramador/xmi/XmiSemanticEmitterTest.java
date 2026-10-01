package com.diagramador.xmi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramador.api.ModelResource;
import com.diagramador.api.ModelResource.AttributeResource;
import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.api.ModelResource.ConstraintResource;
import com.diagramador.api.ModelResource.OperationResource;
import com.diagramador.api.ModelResource.ParameterResource;
import com.diagramador.api.ModelResource.RelationshipEndResource;
import com.diagramador.api.ModelResource.RelationshipResource;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
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
        assertThat(findOptional(document, XMI_NAMESPACE, "Extension")).isNull();

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
        assertThat(allElements(document)).noneMatch(element -> element.hasAttribute("value")
                && element.getAttribute("value").equals("*"));

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
        assertThat(result.losses()).containsExactly(new XmiEmissionResult.Loss("constraint_severity", 1));
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

    private static List<String> childNames(Element parent) {
        List<String> names = new ArrayList<>();
        for (int index = 0; index < parent.getChildNodes().getLength(); index++) {
            if (parent.getChildNodes().item(index) instanceof Element child) {
                names.add(child.getLocalName());
            }
        }
        return names;
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
            for (String attribute : List.of("general", "client", "supplier", "association")) {
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
    }
}
