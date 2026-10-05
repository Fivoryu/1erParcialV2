package com.diagramador.xmi;

import com.diagramador.api.ModelResource;
import com.diagramador.api.ModelResource.AttributeResource;
import com.diagramador.api.ModelResource.ClassifierResource;
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
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.TargetType;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Emits the semantic UML model and measured EA extension from the immutable API snapshot. */
public final class XmiSemanticEmitter {

    private static final String XMI_NAMESPACE = "http://schema.omg.org/spec/XMI/2.1";
    private static final String UML_NAMESPACE = "http://schema.omg.org/spec/UML/2.1";
    /**
     * The dialect marker Enterprise Architect reads to decide whether a document speaks its own dialect. Measured
     * against a live repository: with any other {@code exporter} value EA discards every {@code xmi:id} in the document
     * and never processes {@code <diagrams>}, so the file imports as a foreign document and no figure can bind. This is
     * a measured interoperability requirement, not a claim about who produced the file; the real provenance travels in
     * the document comment below. Evidence: {@code odd/tasks/xmi-intercambio.md} section 10.2.
     */
    private static final String DIALECT_EXPORTER = "Enterprise Architect";
    /** The dialect version Enterprise Architect reports for the measured fixture; also the extension's extenderID. */
    private static final String EA_DIALECT_VERSION = "6.5";
    /** Where the document states who produced it, since the dialect marker above cannot. */
    private static final String PROVENANCE = "UML Class Diagrammer 0.1.0";
    private static final String MODEL_NAME = "EA_Model";
    private static final String EA_CLASS_DIAGRAM_TYPE = "Logical";
    /**
     * Measured minimal diagram-level feature visibility keys. Without this block EA imports the diagram with feature
     * visibility off (t_diagram AttPub/AttPri/AttPro = False) and draws no attributes or operations at all. This is
     * the measured minimal sufficient subset: copying the full 50-key block of EA's own export would import foreign
     * DocSize, PaperSize and Zoom page preferences this application does not model. Evidence:
     * {@code odd/tasks/xmi-compartimentos-ea.md} (measurement M1-M8, decision XCE-03).
     */
    private static final String EA_DIAGRAM_FEATURE_VISIBILITY =
            "ShowPrivate=1;ShowProtected=1;ShowPublic=1;OpParams=1;";
    private static final String EA_PRIMITIVES_PACKAGE_ID = "EAPrimitiveTypesPackage";
    private static final String EA_JAVA_TYPES_PACKAGE_ID = "EAJavaTypesPackage";

    public XmiEmissionResult emit(ModelResource snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Context context = new Context(snapshot.classifiers());
        Document document = composeDocument(snapshot, context);
        return new XmiEmissionResult(serialize(document), context.losses());
    }

    /** The X3.2 extension is deliberately composed here, alongside the semantic model. */
    private Document composeDocument(ModelResource snapshot, Context context) {
        Document document = newDocument();
        Element root = document.createElementNS(XMI_NAMESPACE, "xmi:XMI");
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xmi", XMI_NAMESPACE);
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:uml", UML_NAMESPACE);
        root.setAttributeNS(XMI_NAMESPACE, "xmi:version", "2.1");
        document.appendChild(root);

        Element documentation = document.createElementNS(XMI_NAMESPACE, "xmi:Documentation");
        documentation.setAttribute("exporter", DIALECT_EXPORTER);
        documentation.setAttribute("exporterVersion", EA_DIALECT_VERSION);
        root.appendChild(documentation);
        // A comment node is invisible to every dialect reader, so provenance stays truthful without changing the
        // dialect markers Enterprise Architect keys its import on.
        document.insertBefore(document.createComment(
                " Produced by " + PROVENANCE
                        + ". exporter/exporterVersion declare the XMI dialect Enterprise Architect reads, not the"
                        + " tool that wrote the file. "), root);

        Element model = document.createElementNS(UML_NAMESPACE, "uml:Model");
        setXmiAttribute(model, "type", "uml:Model");
        setXmiAttribute(model, "id", context.registerId(XmiIdCodec.packageId(snapshot.id())));
        model.setAttribute("name", MODEL_NAME);
        model.setAttribute("visibility", "public");
        root.appendChild(model);

        for (ClassifierResource classifier : context.roots()) {
            appendClassifier(document, model, classifier, context);
        }
        appendRelationships(document, model, snapshot.relationships(), context);
        appendPrimitiveTypes(document, model, context);
        appendExtension(document, root, snapshot, context);
        return document;
    }

    private void appendClassifier(Document document, Element parent, ClassifierResource classifier, Context context) {
        String xmiType = classifierType(classifier.kind());
        Element element = document.createElementNS(UML_NAMESPACE, "packagedElement");
        setXmiAttribute(element, "type", "uml:" + xmiType);
        String id = classifier.kind() == ClassifierKind.PACKAGE
                ? XmiIdCodec.packageId(classifier.id())
                : XmiIdCodec.classifierId(classifier.id());
        setXmiAttribute(element, "id", context.registerId(id));
        element.setAttribute("name", Objects.requireNonNull(classifier.name(), "classifier.name"));
        context.classifierElements.put(classifier.id(), element);
        parent.appendChild(element);

        if (classifier.kind() == ClassifierKind.CLASS || classifier.kind() == ClassifierKind.ABSTRACT_CLASS) {
            if (classifier.isAbstract() || classifier.kind() == ClassifierKind.ABSTRACT_CLASS) {
                element.setAttribute("isAbstract", "true");
            }
        } else if (classifier.kind() == ClassifierKind.INTERFACE && classifier.isAbstract()) {
            element.setAttribute("isAbstract", "true");
        }

        for (AttributeResource attribute : orderedAttributes(classifier)) {
            appendAttribute(document, element, attribute, classifier.kind() == ClassifierKind.ENUMERATION, context);
        }
        for (OperationResource operation : orderedOperations(classifier)) {
            appendOperation(document, element, operation, context);
        }
        appendConstraints(document, element, classifier.constraints(), context);

        for (ClassifierResource child : context.children(classifier.id())) {
            appendClassifier(document, element, child, context);
        }
    }

    private void appendAttribute(Document document, Element owner, AttributeResource attribute,
            boolean enumerationConstant, Context context) {
        if (!enumerationConstant && !"ATTRIBUTE".equals(attribute.kind())) {
            context.loss("unsupported_attribute_kind");
        }
        Element element = document.createElementNS(UML_NAMESPACE, "ownedAttribute");
        setXmiAttribute(element, "type", "uml:Property");
        setXmiAttribute(element, "id", context.registerId(XmiIdCodec.classifierId(attribute.id())));
        element.setAttribute("name", Objects.requireNonNull(attribute.name(), "attribute.name"));
        String visibility = visibility(attribute.visibility(), context);
        if (visibility != null) {
            element.setAttribute("visibility", visibility);
        }
        owner.appendChild(element);

        appendMultiplicity(document, element, attribute.multiplicity(), context);
        if (!enumerationConstant) {
            appendTypeReference(document, element, attribute.type(), context);
        }
        if (attribute.defaultValue() != null) {
            Element defaultValue = document.createElementNS(UML_NAMESPACE, "defaultValue");
            setXmiAttribute(defaultValue, "type", "uml:LiteralString");
            defaultValue.setAttribute("value", attribute.defaultValue());
            element.appendChild(defaultValue);
        }
    }

    private void appendOperation(Document document, Element owner, OperationResource operation, Context context) {
        Element element = document.createElementNS(UML_NAMESPACE, "ownedOperation");
        setXmiAttribute(element, "id", context.registerId(XmiIdCodec.classifierId(operation.id())));
        element.setAttribute("name", Objects.requireNonNull(operation.name(), "operation.name"));
        String visibility = visibility(operation.visibility(), context);
        if (visibility != null) {
            element.setAttribute("visibility", visibility);
        }
        element.setAttribute("concurrency", "sequential");
        element.setAttribute("isAbstract", Boolean.toString(operation.isAbstract()));
        element.setAttribute("isStatic", Boolean.toString(operation.isStatic()));
        owner.appendChild(element);

        List<ParameterResource> parameters = orderedParameters(operation);
        boolean hasReturnParameter = parameters.stream().anyMatch(parameter -> isReturn(parameter.direction()));
        for (ParameterResource parameter : parameters) {
            appendParameter(document, element, parameter, context);
        }
        if (!hasReturnParameter && (operation.returnTypeReferenceId() != null || operation.returnType() != null)) {
            Element returnParameter = document.createElementNS(UML_NAMESPACE, "ownedParameter");
            setXmiAttribute(returnParameter, "type", "uml:Parameter");
            setXmiAttribute(returnParameter, "id", context.registerId(
                    XmiIdCodec.returnParameterId(operation.id())));
            returnParameter.setAttribute("name", "return");
            returnParameter.setAttribute("direction", "return");
            appendParameterType(document, returnParameter, operation.returnType(), context);
            element.appendChild(returnParameter);
        }
    }

    private void appendParameter(Document document, Element owner, ParameterResource parameter, Context context) {
        Element element = document.createElementNS(UML_NAMESPACE, "ownedParameter");
        setXmiAttribute(element, "type", "uml:Parameter");
        String parameterId = isReturn(parameter.direction())
                ? XmiIdCodec.returnParameterId(parameter.id())
                : XmiIdCodec.classifierId(parameter.id());
        setXmiAttribute(element, "id", context.registerId(parameterId));
        element.setAttribute("name", Objects.requireNonNull(parameter.name(), "parameter.name"));
        if (parameter.direction() != null && !parameter.direction().isBlank()) {
            String direction = parameter.direction().toLowerCase(Locale.ROOT);
            if (!Set.of("in", "out", "inout", "return").contains(direction)) {
                context.loss("unsupported_parameter_direction");
            }
            element.setAttribute("direction", direction);
        } else {
            context.loss("unsupported_parameter_direction");
        }
        appendParameterType(document, element, parameter.type(), context);
        owner.appendChild(element);
    }

    private void appendParameterType(Document document, Element parameter, String typeName, Context context) {
        String typeId = context.resolveType(typeName);
        if (typeId == null) {
            parameter.appendChild(document.createElementNS(UML_NAMESPACE, "type"));
            return;
        }
        parameter.setAttribute("type", typeId);
    }

    private void appendConstraints(Document document, Element owner, List<ConstraintResource> constraints,
            Context context) {
        for (ConstraintResource constraint : sortedConstraints(constraints)) {
            Element rule = document.createElementNS(UML_NAMESPACE, "ownedRule");
            setXmiAttribute(rule, "type", "uml:Constraint");
            setXmiAttribute(rule, "id", context.registerId(XmiIdCodec.classifierId(constraint.id())));
            Element specification = document.createElementNS(UML_NAMESPACE, "specification");
            setXmiAttribute(specification, "type", "uml:OpaqueExpression");
            if (constraint.expression() != null) {
                Element body = document.createElementNS(UML_NAMESPACE, "body");
                body.setTextContent(constraint.expression());
                specification.appendChild(body);
            } else {
                context.loss("unresolved_constraint_expression");
            }
            rule.appendChild(specification);
            owner.appendChild(rule);
            if (constraint.severity() != null) {
                context.loss("constraint_severity");
            }
        }
    }

    private void appendRelationships(Document document, Element model, List<RelationshipResource> relationships,
            Context context) {
        for (RelationshipResource relationship : sortedRelationships(relationships)) {
            List<RelationshipEndResource> ends = orderedEnds(relationship);
            if (ends.size() < 2) {
                context.loss("relationship_missing_ends");
                continue;
            }
            if (ends.size() != 2) {
                context.loss("invalid_relationship_end_count");
                continue;
            }
            if (ends.stream().anyMatch(end -> !context.emittableClassifierIds.contains(end.classifierId()))) {
                long unresolvedEnds = ends.stream()
                        .filter(end -> !context.emittableClassifierIds.contains(end.classifierId())).count();
                context.loss("relationship_unresolved_end", Math.toIntExact(unresolvedEnds));
                continue;
            }

            RelationshipKind kind = relationshipKind(relationship.kind());
            if (kind == null) {
                context.loss("unsupported_relationship_kind");
                continue;
            }
            long explicitNavigability = ends.stream()
                    .filter(end -> end.navigability() != null && end.navigability() != NavigationKind.UNSPECIFIED)
                    .count();
            context.loss("unsupported_relationship_navigability", Math.toIntExact(explicitNavigability));
            switch (kind) {
                case ASSOCIATION, AGGREGATION, COMPOSITION ->
                    appendAssociation(document, model, relationship, ends, kind, context);
                case GENERALIZATION -> appendGeneralization(document, relationship, ends, context);
                case INTERFACE_REALIZATION ->
                    appendDirectedRelationship(document, model, relationship, ends, "Realization", context);
                case DEPENDENCY ->
                    appendDirectedRelationship(document, model, relationship, ends, "Dependency", context);
            }
        }
    }

    private void appendAssociation(Document document, Element model, RelationshipResource relationship,
            List<RelationshipEndResource> ends, RelationshipKind kind, Context context) {
        String requiredAggregation = switch (kind) {
            case AGGREGATION -> "shared";
            case COMPOSITION -> "composite";
            default -> null;
        };
        if (requiredAggregation != null && ends.stream().noneMatch(end -> requiredAggregation
                .equalsIgnoreCase(Objects.requireNonNullElse(end.aggregationKind(), "")))) {
            context.loss("relationship_aggregation_kind_mismatch");
        }
        Element association = document.createElementNS(UML_NAMESPACE, "packagedElement");
        setXmiAttribute(association, "type", "uml:Association");
        String associationId = context.registerId(XmiIdCodec.classifierId(relationship.id()));
        setXmiAttribute(association, "id", associationId);
        setOptionalAttribute(association, "name", relationship.name());
        model.appendChild(association);

        for (RelationshipEndResource end : ends) {
            Element memberEnd = document.createElementNS(UML_NAMESPACE, "memberEnd");
            setXmiAttribute(memberEnd, "idref", XmiIdCodec.classifierId(end.id()));
            association.appendChild(memberEnd);
        }
        for (RelationshipEndResource end : ends) {
            Element ownedEnd = document.createElementNS(UML_NAMESPACE, "ownedEnd");
            setXmiAttribute(ownedEnd, "type", "uml:Property");
            setXmiAttribute(ownedEnd, "id", context.registerId(XmiIdCodec.classifierId(end.id())));
            setOptionalAttribute(ownedEnd, "name", end.role());
            ownedEnd.setAttribute("visibility", "public");
            ownedEnd.setAttribute("association", associationId);
            String aggregation = aggregation(end.aggregationKind(), context);
            if (aggregation != null) {
                ownedEnd.setAttribute("aggregation", aggregation);
            }
            Element type = document.createElementNS(UML_NAMESPACE, "type");
            setXmiAttribute(type, "idref", classifierXmiId(context.classifierById.get(end.classifierId())));
            ownedEnd.appendChild(type);
            appendMultiplicity(document, ownedEnd, end.multiplicity(), context);
            association.appendChild(ownedEnd);
        }
        appendConstraints(document, association, relationship.constraints(), context);
    }

    private String aggregation(String rawValue, Context context) {
        if (rawValue == null || rawValue.isBlank()) {
            context.loss("unsupported_aggregation_kind");
            return null;
        }
        String value = rawValue.toLowerCase(Locale.ROOT);
        if (Set.of("none", "shared", "composite").contains(value)) {
            return value;
        }
        context.loss("unsupported_aggregation_kind");
        return null;
    }

    private void appendGeneralization(Document document, RelationshipResource relationship,
            List<RelationshipEndResource> ends, Context context) {
        RelationshipEndResource specific = ends.get(0);
        RelationshipEndResource general = ends.get(1);
        Element specificClassifier = context.classifierElements.get(specific.classifierId());
        if (specificClassifier == null) {
            context.loss("relationship_unresolved_end");
            return;
        }
        Element generalization = document.createElementNS(UML_NAMESPACE, "generalization");
        setXmiAttribute(generalization, "type", "uml:Generalization");
        setXmiAttribute(generalization, "id", context.registerId(XmiIdCodec.classifierId(relationship.id())));
        generalization.setAttribute("general",
                classifierXmiId(context.classifierById.get(general.classifierId())));
        specificClassifier.appendChild(generalization);
        appendConstraints(document, generalization, relationship.constraints(), context);
        if (relationship.name() != null) {
            context.loss("unsupported_relationship_name");
        }
    }

    private void appendDirectedRelationship(Document document, Element model, RelationshipResource relationship,
            List<RelationshipEndResource> ends, String xmiType, Context context) {
        Element directed = document.createElementNS(UML_NAMESPACE, "packagedElement");
        setXmiAttribute(directed, "type", "uml:" + xmiType);
        setXmiAttribute(directed, "id", context.registerId(XmiIdCodec.classifierId(relationship.id())));
        setOptionalAttribute(directed, "name", relationship.name());
        directed.setAttribute("client",
                classifierXmiId(context.classifierById.get(ends.get(0).classifierId())));
        directed.setAttribute("supplier",
                classifierXmiId(context.classifierById.get(ends.get(1).classifierId())));
        if ("Realization".equals(xmiType)) {
            directed.setAttribute("visibility", "public");
        }
        model.appendChild(directed);
        appendConstraints(document, directed, relationship.constraints(), context);
    }

    private void appendMultiplicity(Document document, Element owner, MultiplicityKind multiplicity,
            Context context) {
        if (multiplicity == null) {
            context.loss("unsupported_multiplicity");
            return;
        }
        String lower;
        String upper;
        switch (multiplicity) {
            case EXACTLY_ONE -> {
                lower = "1";
                upper = "1";
            }
            case ZERO_OR_ONE -> {
                lower = "0";
                upper = "1";
            }
            case ONE_OR_MANY -> {
                lower = "1";
                upper = "-1";
            }
            case ZERO_OR_MANY -> {
                lower = "0";
                upper = "-1";
            }
            case RANGE -> {
                context.loss("unsupported_multiplicity_range");
                return;
            }
            default -> throw new IllegalStateException("Unhandled multiplicity: " + multiplicity);
        }

        String ownerId = owner.getAttributeNS(XMI_NAMESPACE, "id");
        Element lowerValue = document.createElementNS(UML_NAMESPACE, "lowerValue");
        setXmiAttribute(lowerValue, "type", "uml:LiteralInteger");
        setXmiAttribute(lowerValue, "id", context.registerId(
                XmiIdCodec.multiplicityValueId(ownerId, "lower", false)));
        lowerValue.setAttribute("value", lower);
        owner.appendChild(lowerValue);

        boolean unlimited = upper.equals("-1");
        Element upperValue = document.createElementNS(UML_NAMESPACE, "upperValue");
        setXmiAttribute(upperValue, "type", unlimited
                ? "uml:LiteralUnlimitedNatural" : "uml:LiteralInteger");
        setXmiAttribute(upperValue, "id", context.registerId(
                XmiIdCodec.multiplicityValueId(ownerId, "upper", unlimited)));
        upperValue.setAttribute("value", upper);
        owner.appendChild(upperValue);
    }

    private void appendTypeReference(Document document, Element owner, String typeName, Context context) {
        // A TypeReference UUID identifies the relationship row, not its target. Only the snapshot's resolved name
        // can map it back to a classifier; its name-only primitive references are materialized deterministically.
        String id = context.resolveType(typeName);
        if (id == null) {
            return;
        }
        Element type = document.createElementNS(UML_NAMESPACE, "type");
        setXmiAttribute(type, "idref", id);
        owner.appendChild(type);
    }

    private void appendPrimitiveTypes(Document document, Element model, Context context) {
        List<Map.Entry<String, String>> primitiveTypes = new ArrayList<>(context.primitiveTypes.entrySet());
        primitiveTypes.sort(Map.Entry.<String, String>comparingByValue().thenComparing(Map.Entry.comparingByKey()));
        for (Map.Entry<String, String> primitive : primitiveTypes) {
            Element element = document.createElementNS(UML_NAMESPACE, "packagedElement");
            setXmiAttribute(element, "type", "uml:PrimitiveType");
            setXmiAttribute(element, "id", context.registerId(primitive.getKey()));
            element.setAttribute("name", primitive.getValue());
            model.appendChild(element);
        }
    }

    private void appendExtension(Document document, Element root, ModelResource snapshot, Context context) {
        Element extension = document.createElementNS(XMI_NAMESPACE, "xmi:Extension");
        extension.setAttribute("extender", "Enterprise Architect");
        extension.setAttribute("extenderID", EA_DIALECT_VERSION);
        root.appendChild(extension);

        ViewResource defaultView = defaultView(snapshot, context);
        appendExtensionElements(document, extension, snapshot, defaultView, context);
        Element connectors = document.createElement("connectors");
        extension.appendChild(connectors);
        appendExtensionConnectors(document, connectors, snapshot, context);
        appendExtensionPrimitiveTypes(document, extension, context);
        extension.appendChild(document.createElement("profiles"));
        Element diagrams = document.createElement("diagrams");
        extension.appendChild(diagrams);
        if (defaultView != null) {
            appendExtensionDiagram(document, diagrams, snapshot, defaultView, context);
        }
    }

    private ViewResource defaultView(ModelResource snapshot, Context context) {
        List<ViewResource> defaults = snapshot.views().stream()
                .filter(ViewResource::isDefault)
                .sorted(Comparator.comparing(ViewResource::id, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        if (defaults.size() > 1) {
            context.loss("multiple_default_views", defaults.size() - 1);
        }
        return defaults.isEmpty() ? null : defaults.get(0);
    }

    private void appendExtensionElements(Document document, Element extension, ModelResource snapshot,
            ViewResource defaultView, Context context) {
        Element elements = document.createElement("elements");
        extension.appendChild(elements);
        for (ClassifierResource classifier : extensionClassifiers(defaultView, context)) {
            appendExtensionElement(document, elements, classifier, context);
        }
    }

    private List<ClassifierResource> extensionClassifiers(ViewResource defaultView, Context context) {
        Set<UUID> selectedIds = new HashSet<>();
        if (defaultView != null) {
            for (GeometryResource geometry : defaultView.elementGeometry()) {
                if (geometry.targetType() != TargetType.CLASSIFIER) {
                    continue;
                }
                ClassifierResource classifier = context.classifierById.get(geometry.targetId());
                if (classifier == null || !context.emittableClassifierIds.contains(classifier.id())) {
                    context.loss("extension_unresolved_placement");
                    continue;
                }
                selectedIds.add(classifier.id());
                UUID namespaceId = classifier.namespaceId();
                while (namespaceId != null) {
                    ClassifierResource namespace = context.classifierById.get(namespaceId);
                    if (namespace == null || namespace.kind() != ClassifierKind.PACKAGE) {
                        break;
                    }
                    selectedIds.add(namespace.id());
                    namespaceId = namespace.namespaceId();
                }
            }
        }
        return selectedIds.stream()
                .map(context.classifierById::get)
                .sorted(Comparator.comparingInt((ClassifierResource classifier) -> classifierDepth(classifier, context))
                        .thenComparing(classifier -> classifier.kind() == ClassifierKind.PACKAGE ? 0 : 1)
                        .thenComparing(ClassifierResource::name, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(ClassifierResource::id))
                .toList();
    }

    private void appendExtensionConnectors(Document document, Element connectors, ModelResource snapshot,
            Context context) {
        int emitted = 0;
        for (RelationshipResource relationship : sortedRelationships(snapshot.relationships())) {
            List<RelationshipEndResource> ends = orderedEnds(relationship);
            if (ends.size() != 2 || relationshipKind(relationship.kind()) == null
                    || ends.stream().anyMatch(end -> !context.emittableClassifierIds.contains(end.classifierId()))) {
                continue;
            }
            appendExtensionConnector(document, connectors, relationship, ends, context);
            emitted++;
        }
        context.loss("extension_connector_vendor_metadata", emitted);
    }

    private void appendExtensionConnector(Document document, Element connectors,
            RelationshipResource relationship, List<RelationshipEndResource> ends, Context context) {
        Element connector = document.createElement("connector");
        setXmiAttribute(connector, "idref", XmiIdCodec.classifierId(relationship.id()));
        setOptionalAttribute(connector, "name", relationship.name());
        appendExtensionConnectorEnd(document, connector, "source", ends.get(0), context);
        appendExtensionConnectorEnd(document, connector, "target", ends.get(1), context);

        connector.appendChild(document.createElement("model"));

        Element properties = document.createElement("properties");
        properties.setAttribute("ea_type", extensionRelationshipType(relationship.kind()));
        properties.setAttribute("direction", extensionRelationshipDirection(ends));
        connector.appendChild(properties);

        Element modifiers = document.createElement("modifiers");
        modifiers.setAttribute("isRoot", "false");
        modifiers.setAttribute("isLeaf", "false");
        connector.appendChild(modifiers);

        Element appearance = document.createElement("appearance");
        appearance.setAttribute("linemode", "3");
        appearance.setAttribute("linecolor", "-1");
        appearance.setAttribute("linewidth", "0");
        appearance.setAttribute("seqno", "0");
        appearance.setAttribute("headStyle", "0");
        appearance.setAttribute("lineStyle", "0");
        connector.appendChild(appearance);

        Element labels = document.createElement("labels");
        appendOptionalLabel(labels, "lb", extensionMultiplicity(ends.get(0).multiplicity()));
        appendOptionalLabel(labels, "lt", extensionRoleLabel(ends.get(0).role()));
        appendOptionalLabel(labels, "rb", extensionMultiplicity(ends.get(1).multiplicity()));
        appendOptionalLabel(labels, "rt", extensionRoleLabel(ends.get(1).role()));
        appendOptionalLabel(labels, "mt", relationship.name());
        connector.appendChild(labels);
        connector.appendChild(document.createElement("style"));
        connectors.appendChild(connector);
    }

    private void appendExtensionConnectorEnd(Document document, Element connector, String endName,
            RelationshipEndResource end, Context context) {
        ClassifierResource classifier = context.classifierById.get(end.classifierId());
        Element endpoint = document.createElement(endName);
        setXmiAttribute(endpoint, "idref", classifierXmiId(classifier));

        Element model = document.createElement("model");
        model.setAttribute("type", classifierType(classifier.kind()));
        model.setAttribute("name", Objects.requireNonNull(classifier.name(), "classifier.name"));
        endpoint.appendChild(model);

        Element role = document.createElement("role");
        setOptionalAttribute(role, "name", end.role());
        role.setAttribute("visibility", "Public");
        endpoint.appendChild(role);

        Element type = document.createElement("type");
        String multiplicity = extensionMultiplicity(end.multiplicity());
        if (multiplicity == null) {
            context.loss("extension_connector_multiplicity");
        } else {
            type.setAttribute("multiplicity", multiplicity);
        }
        String aggregation = extensionAggregation(end.aggregationKind());
        if (aggregation == null) {
            context.loss("extension_connector_aggregation");
        } else {
            type.setAttribute("aggregation", aggregation);
        }
        endpoint.appendChild(type);

        Element modifiers = document.createElement("modifiers");
        modifiers.setAttribute("isOrdered", "false");
        modifiers.setAttribute("isNavigable", Boolean.toString(end.navigability() == NavigationKind.NAVIGABLE));
        endpoint.appendChild(modifiers);

        Element style = document.createElement("style");
        String styleValue = end.navigability() == NavigationKind.NAVIGABLE
                ? "Navigable=Navigable;Owned=0;" : "Owned=0;Navigable=Unspecified;";
        style.setAttribute("value", styleValue);
        endpoint.appendChild(style);
        connector.appendChild(endpoint);
    }

    private String extensionRelationshipType(String rawKind) {
        RelationshipKind kind = relationshipKind(rawKind);
        return switch (kind) {
            case ASSOCIATION -> "Association";
            case AGGREGATION, COMPOSITION -> "Aggregation";
            case GENERALIZATION -> "Generalization";
            case INTERFACE_REALIZATION -> "Realisation";
            case DEPENDENCY -> "Dependency";
        };
    }

    private String extensionRelationshipDirection(List<RelationshipEndResource> ends) {
        boolean aggregation = ends.stream().anyMatch(end -> Set.of("shared", "composite")
                .contains(Objects.requireNonNullElse(end.aggregationKind(), "").toLowerCase(Locale.ROOT)));
        return aggregation ? "Destination -> Source" : "Unspecified";
    }

    private String extensionMultiplicity(MultiplicityKind multiplicity) {
        if (multiplicity == null) {
            return null;
        }
        return switch (multiplicity) {
            case EXACTLY_ONE -> "1";
            case ZERO_OR_ONE -> "0..1";
            case ONE_OR_MANY -> "1..*";
            case ZERO_OR_MANY -> "0..*";
            case RANGE -> null;
        };
    }

    private String extensionAggregation(String aggregation) {
        if (aggregation == null) {
            return null;
        }
        String normalized = aggregation.toLowerCase(Locale.ROOT);
        return Set.of("none", "shared", "composite").contains(normalized) ? normalized : null;
    }

    private String extensionRoleLabel(String role) {
        return role == null || role.isBlank() ? null : "+" + role;
    }

    private void appendOptionalLabel(Element labels, String name, String value) {
        if (value != null && !value.isBlank()) {
            labels.setAttribute(name, value);
        }
    }

    private void appendExtensionDiagram(Document document, Element diagrams, ModelResource snapshot,
            ViewResource view, Context context) {
        Element diagram = document.createElement("diagram");
        setXmiAttribute(diagram, "id", context.registerId(XmiIdCodec.classifierId(view.id())));
        diagrams.appendChild(diagram);

        String packageId = XmiIdCodec.packageId(snapshot.id());
        Element model = document.createElement("model");
        model.setAttribute("package", packageId);
        model.setAttribute("localID", diagramLocalId(view.id()));
        model.setAttribute("owner", packageId);
        // The single emitted diagram is attached directly to its model package.
        model.setAttribute("tpos", "0");
        diagram.appendChild(model);

        Element properties = document.createElement("properties");
        setOptionalAttribute(properties, "name", view.name());
        // ModelResource has no diagram-kind field; this UML class diagram uses EA's measured Logical kind.
        properties.setAttribute("type", EA_CLASS_DIAGRAM_TYPE);
        diagram.appendChild(properties);
        // ViewResource carries the name, but no documentation.
        context.loss("extension_diagram_properties_metadata");

        Element project = document.createElement("project");
        project.setAttribute("version", Long.toString(snapshot.revision()));
        diagram.appendChild(project);
        // Author and timestamps are absent from ModelResource; never synthesize them from ambient state.
        context.loss("extension_diagram_project_metadata", 3);
        // Diagram-level visibility keys, not optional decoration: without them EA imports the diagram with feature
        // visibility off and renders neither attributes nor operations, even though the semantic model carries them.
        Element style1 = document.createElement("style1");
        style1.setAttribute("value", EA_DIAGRAM_FEATURE_VISIBILITY);
        diagram.appendChild(style1);
        diagram.appendChild(document.createElement("style2"));
        context.loss("extension_diagram_style2_metadata");
        diagram.appendChild(document.createElement("swimlanes"));
        context.loss("extension_diagram_swimlanes_metadata");
        diagram.appendChild(document.createElement("matrixitems"));
        context.loss("extension_diagram_matrixitems_metadata");
        diagram.appendChild(document.createElement("extendedProperties"));

        Element placements = document.createElement("elements");
        diagram.appendChild(placements);
        Map<UUID, String> duidByClassifier = appendExtensionFigurePlacements(document, placements, view, context);
        appendExtensionConnectorPlacements(document, placements, snapshot, duidByClassifier, context);
        context.loss("extension_connector_waypoints", view.connectorPath().size());
    }

    private String diagramLocalId(UUID viewId) {
        // EA expects a numeric local identifier; derive a stable positive surrogate from the canonical view UUID.
        return Integer.toString(Math.floorMod(viewId.hashCode(), Integer.MAX_VALUE) + 1);
    }

    private Map<UUID, String> appendExtensionFigurePlacements(Document document, Element placements,
            ViewResource view, Context context) {
        Map<UUID, String> duidByClassifier = new HashMap<>();
        for (GeometryResource geometry : orderedGeometry(view)) {
            if (geometry.targetType() != TargetType.CLASSIFIER) {
                continue;
            }
            ClassifierResource classifier = context.classifierById.get(geometry.targetId());
            if (classifier == null || !context.emittableClassifierIds.contains(classifier.id())
                    || duidByClassifier.containsKey(classifier.id())) {
                if (classifier != null && duidByClassifier.containsKey(classifier.id())) {
                    context.loss("extension_duplicate_shape_placement");
                }
                continue;
            }
            String subject = classifierXmiId(classifier);
            String duid = context.diagramDuid(view.id(), subject);
            duidByClassifier.put(classifier.id(), duid);

            Element placement = document.createElement("element");
            placement.setAttribute("geometry", "Left=" + geometry.x() + ";Top=" + geometry.y()
                    + ";Right=" + (geometry.x() + geometry.w()) + ";Bottom=" + (geometry.y() + geometry.h()) + ";");
            placement.setAttribute("subject", subject);
            placement.setAttribute("seqno", Integer.toString(geometry.zOrder()));
            placement.setAttribute("style", "DUID=" + duid + ";");
            placements.appendChild(placement);
        }
        return duidByClassifier;
    }

    private List<GeometryResource> orderedGeometry(ViewResource view) {
        return view.elementGeometry().stream()
                .sorted(Comparator.comparingInt(GeometryResource::zOrder)
                        .thenComparing(GeometryResource::targetId, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(GeometryResource::id, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    private void appendExtensionConnectorPlacements(Document document, Element placements, ModelResource snapshot,
            Map<UUID, String> duidByClassifier, Context context) {
        for (RelationshipResource relationship : sortedRelationships(snapshot.relationships())) {
            List<RelationshipEndResource> ends = orderedEnds(relationship);
            if (ends.size() != 2 || relationshipKind(relationship.kind()) == null) {
                continue;
            }
            String sourceDuid = duidByClassifier.get(ends.get(0).classifierId());
            String targetDuid = duidByClassifier.get(ends.get(1).classifierId());
            if (sourceDuid == null || targetDuid == null
                    || !context.emittableClassifierIds.contains(ends.get(0).classifierId())
                    || !context.emittableClassifierIds.contains(ends.get(1).classifierId())) {
                continue;
            }
            Element placement = document.createElement("element");
            placement.setAttribute("geometry", "SX=0;SY=0;EX=0;EY=0;Path=;");
            placement.setAttribute("subject", XmiIdCodec.classifierId(relationship.id()));
            placement.setAttribute("style", "Mode=3;EOID=" + targetDuid + ";SOID=" + sourceDuid
                    + ";Color=-1;LWidth=0;Hidden=0;");
            placements.appendChild(placement);
        }
    }

    private int classifierDepth(ClassifierResource classifier, Context context) {
        int depth = 0;
        UUID namespaceId = classifier.namespaceId();
        Set<UUID> visited = new HashSet<>();
        while (namespaceId != null && visited.add(namespaceId)) {
            ClassifierResource namespace = context.classifierById.get(namespaceId);
            if (namespace == null || namespace.kind() != ClassifierKind.PACKAGE) {
                break;
            }
            depth++;
            namespaceId = namespace.namespaceId();
        }
        return depth;
    }

    private void appendExtensionElement(Document document, Element elements, ClassifierResource classifier,
            Context context) {
        Element element = document.createElement("element");
        setXmiAttribute(element, "idref", classifierXmiId(classifier));
        setXmiAttribute(element, "type", "uml:" + classifierType(classifier.kind()));
        element.setAttribute("name", Objects.requireNonNull(classifier.name(), "classifier.name"));
        elements.appendChild(element);
        // Scope, documentation, EA-local identifiers, and per-element styling are not in ModelResource.
        context.loss("extension_element_vendor_metadata");

        Element model = document.createElement("model");
        model.setAttribute("ea_eleType", classifier.kind() == ClassifierKind.PACKAGE ? "package" : "element");
        ClassifierResource namespace = context.classifierById.get(classifier.namespaceId());
        if (namespace != null && namespace.kind() == ClassifierKind.PACKAGE) {
            model.setAttribute("package", XmiIdCodec.packageId(namespace.id()));
        }
        element.appendChild(model);

        Element properties = document.createElement("properties");
        properties.setAttribute("sType", classifierType(classifier.kind()));
        if (classifier.kind() != ClassifierKind.PACKAGE) {
            properties.setAttribute("isAbstract", Boolean.toString(classifier.isAbstract()
                    || classifier.kind() == ClassifierKind.ABSTRACT_CLASS));
        }
        element.appendChild(properties);

        if (!classifier.attributes().isEmpty()) {
            Element attributes = document.createElement("attributes");
            element.appendChild(attributes);
            for (AttributeResource attribute : orderedAttributes(classifier)) {
                appendExtensionAttribute(document, attributes, attribute, context);
            }
        }
    }

    private void appendExtensionAttribute(Document document, Element attributes, AttributeResource attribute,
            Context context) {
        Element element = document.createElement("attribute");
        setXmiAttribute(element, "idref", XmiIdCodec.classifierId(attribute.id()));
        element.setAttribute("name", Objects.requireNonNull(attribute.name(), "attribute.name"));
        String scope = extensionVisibility(attribute.visibility());
        if (scope != null) {
            element.setAttribute("scope", scope);
        }
        attributes.appendChild(element);

        Element coords = document.createElement("coords");
        coords.setAttribute("ordered", "0");
        element.appendChild(coords);
        context.loss("attribute_layout_coordinates");

        Element containment = document.createElement("containment");
        containment.setAttribute("position", Integer.toString(attribute.ordinal()));
        element.appendChild(containment);
        context.loss("attribute_layout_position_approximation");
        context.loss("attribute_layout_containment_kind");

        if (attribute.multiplicity() == MultiplicityKind.EXACTLY_ONE) {
            Element bounds = document.createElement("bounds");
            bounds.setAttribute("lower", "1");
            bounds.setAttribute("upper", "1");
            element.appendChild(bounds);
        } else {
            context.loss("attribute_layout_bounds");
        }
    }

    private String extensionVisibility(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        return switch (rawValue.toLowerCase(Locale.ROOT)) {
            case "public" -> "Public";
            case "private" -> "Private";
            case "protected" -> "Protected";
            case "package" -> "Package";
            default -> null;
        };
    }

    private void appendExtensionPrimitiveTypes(Document document, Element extension, Context context) {
        Element primitiveTypes = document.createElement("primitivetypes");
        extension.appendChild(primitiveTypes);

        Element rootPackage = document.createElement("packagedElement");
        setXmiAttribute(rootPackage, "type", "uml:Package");
        setXmiAttribute(rootPackage, "id", EA_PRIMITIVES_PACKAGE_ID);
        rootPackage.setAttribute("name", "EA_PrimitiveTypes_Package");
        rootPackage.setAttribute("visibility", "public");
        primitiveTypes.appendChild(rootPackage);

        Element javaPackage = document.createElement("packagedElement");
        setXmiAttribute(javaPackage, "type", "uml:Package");
        setXmiAttribute(javaPackage, "id", EA_JAVA_TYPES_PACKAGE_ID);
        javaPackage.setAttribute("name", "EA_Java_Types_Package");
        javaPackage.setAttribute("visibility", "public");
        rootPackage.appendChild(javaPackage);

        // The snapshot has type names, but no measured way to assign them to EA's Java/none namespaces.
        context.loss("extension_primitive_type_catalog", context.primitiveTypes.size());
    }

    private String visibility(String rawValue, Context context) {
        if (rawValue == null) {
            context.loss("unsupported_visibility");
            return null;
        }
        String normalized = rawValue.toLowerCase(Locale.ROOT);
        if (Set.of("public", "private", "protected", "package").contains(normalized)) {
            return normalized;
        }
        context.loss("unsupported_visibility");
        return null;
    }

    private String classifierXmiId(ClassifierResource classifier) {
        return classifier.kind() == ClassifierKind.PACKAGE
                ? XmiIdCodec.packageId(classifier.id())
                : XmiIdCodec.classifierId(classifier.id());
    }

    private String classifierType(ClassifierKind kind) {
        return switch (kind) {
            case CLASS, ABSTRACT_CLASS -> "Class";
            case INTERFACE -> "Interface";
            case ENUMERATION -> "Enumeration";
            case PACKAGE -> "Package";
        };
    }

    private RelationshipKind relationshipKind(String value) {
        if (value == null) {
            return null;
        }
        try {
            return RelationshipKind.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean isReturn(String direction) {
        return direction != null && direction.equalsIgnoreCase("return");
    }

    private List<AttributeResource> orderedAttributes(ClassifierResource classifier) {
        return classifier.attributes().stream()
                .sorted(Comparator.comparingInt(AttributeResource::ordinal)
                        .thenComparing(AttributeResource::name, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(AttributeResource::id))
                .toList();
    }

    private List<OperationResource> orderedOperations(ClassifierResource classifier) {
        return classifier.operations().stream()
                .sorted(Comparator.comparingInt(OperationResource::ordinal)
                        .thenComparing(OperationResource::name, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(OperationResource::id))
                .toList();
    }

    private List<ParameterResource> orderedParameters(OperationResource operation) {
        return operation.parameters().stream()
                .sorted(Comparator.comparingInt(ParameterResource::ordinal)
                        .thenComparing(ParameterResource::name, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(ParameterResource::id))
                .toList();
    }

    private List<RelationshipEndResource> orderedEnds(RelationshipResource relationship) {
        return relationship.ends().stream()
                .sorted(Comparator.comparingInt(RelationshipEndResource::ordinal)
                        .thenComparing(RelationshipEndResource::id))
                .toList();
    }

    private List<ConstraintResource> sortedConstraints(List<ConstraintResource> constraints) {
        return constraints.stream().sorted(Comparator.comparing(ConstraintResource::id)).toList();
    }

    private List<RelationshipResource> sortedRelationships(List<RelationshipResource> relationships) {
        return relationships.stream()
                .sorted(Comparator.comparing(RelationshipResource::name,
                                Comparator.nullsFirst(String::compareTo))
                        .thenComparing(RelationshipResource::id))
                .toList();
    }

    private void setXmiAttribute(Element element, String localName, String value) {
        element.setAttributeNS(XMI_NAMESPACE, "xmi:" + localName, value);
    }

    private void setOptionalAttribute(Element element, String name, String value) {
        if (value != null) {
            element.setAttribute(name, value);
        }
    }

    private Document newDocument() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException exception) {
            throw new IllegalStateException("The JDK XML document builder is unavailable.", exception);
        }
    }

    private String serialize(Document document) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.STANDALONE, "no");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString();
        } catch (TransformerException exception) {
            throw new IllegalStateException("The XMI document could not be serialized.", exception);
        }
    }

    private final class Context {

        private final Map<UUID, ClassifierResource> classifierById = new HashMap<>();
        private final Map<String, List<ClassifierResource>> classifiersByName = new HashMap<>();
        private final Map<UUID, List<ClassifierResource>> childrenByNamespace = new HashMap<>();
        private final Map<UUID, Element> classifierElements = new HashMap<>();
        private final Set<UUID> emittableClassifierIds = new HashSet<>();
        private final Set<String> ids = new HashSet<>();
        private final Set<String> diagramDuids = new HashSet<>();
        private final Map<String, String> primitiveTypes = new HashMap<>();
        private final Map<String, Integer> lossCounts = new TreeMap<>();
        private final List<ClassifierResource> roots;

        private Context(List<ClassifierResource> classifiers) {
            List<ClassifierResource> sorted = classifiers.stream()
                    .sorted(Comparator.comparing(ClassifierResource::name,
                                    Comparator.nullsFirst(String::compareTo))
                            .thenComparing(ClassifierResource::id))
                    .toList();
            for (ClassifierResource classifier : sorted) {
                if (classifierById.putIfAbsent(classifier.id(), classifier) != null) {
                    throw new IllegalArgumentException("Classifier UUIDs must be unique in a model snapshot.");
                }
                classifiersByName.computeIfAbsent(classifier.name(), ignored -> new ArrayList<>()).add(classifier);
            }
            List<ClassifierResource> rootClassifiers = new ArrayList<>();
            for (ClassifierResource classifier : sorted) {
                if (classifier.namespaceId() == null) {
                    rootClassifiers.add(classifier);
                    continue;
                }
                ClassifierResource namespace = classifierById.get(classifier.namespaceId());
                if (namespace != null && namespace.kind() == ClassifierKind.PACKAGE) {
                    childrenByNamespace.computeIfAbsent(namespace.id(), ignored -> new ArrayList<>()).add(classifier);
                }
            }
            roots = List.copyOf(rootClassifiers);
            for (ClassifierResource root : roots) {
                markReachable(root);
            }
            for (ClassifierResource classifier : sorted) {
                if (!emittableClassifierIds.contains(classifier.id())) {
                    loss("unresolved_namespace");
                }
            }
        }

        private void markReachable(ClassifierResource classifier) {
            if (!emittableClassifierIds.add(classifier.id())) {
                return;
            }
            for (ClassifierResource child : children(classifier.id())) {
                markReachable(child);
            }
        }

        private List<ClassifierResource> roots() {
            return roots;
        }

        private List<ClassifierResource> children(UUID namespaceId) {
            return childrenByNamespace.getOrDefault(namespaceId, List.of());
        }

        private String resolveType(String typeName) {
            // A name absent from the complete classifier inventory is the primitive name exposed by
            // ProjectApiService. Duplicate classifier names cannot be disambiguated by this snapshot.
            if (typeName == null || typeName.isBlank()) {
                loss("unresolved_type_reference");
                return null;
            }
            String normalizedName = typeName.trim();
            List<ClassifierResource> matches = classifiersByName.getOrDefault(normalizedName, List.of());
            if (matches.size() > 1) {
                loss("ambiguous_type_reference");
                return null;
            }
            if (matches.size() == 1) {
                ClassifierResource classifier = matches.get(0);
                if (!emittableClassifierIds.contains(classifier.id())) {
                    loss("unresolved_type_reference");
                    return null;
                }
                return classifier.kind() == ClassifierKind.PACKAGE
                        ? XmiIdCodec.packageId(classifier.id())
                        : XmiIdCodec.classifierId(classifier.id());
            }

            UUID primitiveUuid = UUID.nameUUIDFromBytes(
                    ("uml-diagrammer:xmi-primitive:" + normalizedName).getBytes(StandardCharsets.UTF_8));
            String primitiveId = XmiIdCodec.classifierId(primitiveUuid);
            primitiveTypes.putIfAbsent(primitiveId, normalizedName);
            return primitiveId;
        }

        private String diagramDuid(UUID diagramId, String subjectId) {
            String identity = "uml-diagrammer:xmi-duid:" + XmiIdCodec.classifierId(diagramId) + ":" + subjectId;
            for (int collision = 0; collision < 1_000_000; collision++) {
                String seed = collision == 0 ? identity : identity + ":" + collision;
                String token = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString()
                        .replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
                if (diagramDuids.add(token)) {
                    return token;
                }
            }
            throw new IllegalStateException("Unable to allocate a unique diagram DUID.");
        }

        private String registerId(String id) {
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate XMI id generated: " + id);
            }
            return id;
        }

        private void loss(String name) {
            loss(name, 1);
        }

        private void loss(String name, int count) {
            if (count > 0) {
                lossCounts.merge(name, count, Integer::sum);
            }
        }

        private List<XmiEmissionResult.Loss> losses() {
            return lossCounts.entrySet().stream()
                    .map(entry -> new XmiEmissionResult.Loss(entry.getKey(), entry.getValue()))
                    .toList();
        }
    }
}
