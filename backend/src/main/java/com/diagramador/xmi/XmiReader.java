package com.diagramador.xmi;

import com.diagramador.api.ModelResource;
import com.diagramador.api.ModelResource.AttributeResource;
import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.api.ModelResource.ConnectorPathResource;
import com.diagramador.api.ModelResource.ConstraintResource;
import com.diagramador.api.ModelResource.GeometryResource;
import com.diagramador.api.ModelResource.NoteLinkResource;
import com.diagramador.api.ModelResource.NoteResource;
import com.diagramador.api.ModelResource.OperationResource;
import com.diagramador.api.ModelResource.ParameterResource;
import com.diagramador.api.ModelResource.RelationshipEndResource;
import com.diagramador.api.ModelResource.RelationshipResource;
import com.diagramador.api.ModelResource.ViewResource;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.AttributeStereotype;
import com.diagramador.domain.ClassifierStereotype;
import com.diagramador.domain.DependencyStereotype;
import com.diagramador.domain.OperationStereotype;
import com.diagramador.domain.Stereotypes;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.TargetType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Pure reader for the measured Enterprise Architect XMI dialect. */
public final class XmiReader {

    private static final String XMI = "http://schema.omg.org/spec/XMI/2.1";
    private static final String UML_NAMESPACE_PREFIX = "http://schema.omg.org/spec/UML/";
    private static final String UML = "http://schema.omg.org/spec/UML/2.1";
    private static final String UUID_NAMESPACE = "ea-xmi-reader:";

    public XmiReadingResult read(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("An XMI document is required.");
        }
        Document document = parse(bytes);
        Element root = document.getDocumentElement();
        if (!"XMI".equals(root.getLocalName())) {
            throw new IllegalArgumentException("The document root must be xmi:XMI.");
        }
        Element modelElement = first(document, UML, "Model");
        if (modelElement == null) {
            throw new IllegalArgumentException(unsupportedDialect(root));
        }
        Element extension = first(document, XMI, "Extension");
        Context context = new Context(modelElement, extension);
        context.indexTypes();

        List<ClassifierResource> classifiers = new ArrayList<>();
        context.readClassifiers(modelElement, null, classifiers);
        List<RelationshipResource> relationships = context.readRelationships();
        List<NoteResource> notes = context.readNotes(classifiers, relationships);
        List<XmiReadingResult.Diagram> diagrams = context.readDiagrams();
        List<ViewResource> views = context.views();
        long revision = context.revision();
        UUID modelId = context.id(modelElement, "model:" + value(modelElement, "name", "EA_Model"));
        UUID projectId = namedUuid("project:" + modelId);
        UUID versionId = namedUuid("version:" + modelId + ":" + revision);
        ModelResource model = new ModelResource(modelId, projectId, revision, versionId, views,
                classifiers, relationships, List.of(), notes);
        return context.result(model, diagrams);
    }

    /**
     * Names the dialect that was actually found, because the missing element is a symptom and the dialect is the
     * finding. Measured against three real Enterprise Architect exports: the UML 2.1 namespace is read, while the XMI
     * 1.1 dialect -which is what EA exports by default- and the UML 2.2 namespace are not. Reporting only a missing
     * {@code uml:Model} sends the reader looking for an element when the answer is a different export option.
     * Evidence: {@code odd/tasks/xmi-intercambio.md} section 11.
     */
    private static String unsupportedDialect(Element root) {
        String namespace = "";
        boolean hasReaderNamespace = false;
        for (int index = 0; index < root.getAttributes().getLength(); index++) {
            Node declaration = root.getAttributes().item(index);
            String value = declaration.getNodeValue();
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(declaration.getNamespaceURI())
                    && value.startsWith(UML_NAMESPACE_PREFIX)) {
                if (namespace.isEmpty()) {
                    namespace = value;
                }
                hasReaderNamespace |= UML.equals(value);
            }
        }
        String version = root.getAttributeNS(XMI, "version");
        if (version.isEmpty()) {
            version = root.getAttribute("xmi.version");
        }
        if ("1.1".equals(version)) {
            return "Unsupported XMI dialect: the document is XMI 1.1, which this reader cannot represent. XMI 1.1 is"
                    + " what Enterprise Architect exports by default; export as XMI 2.1 and import again.";
        }
        if (!namespace.isEmpty() && !hasReaderNamespace) {
            return "Unsupported XMI dialect: the document declares the UML namespace " + namespace + ", and this"
                    + " reader implements " + UML + " (UML 2.1). Export as XMI 2.1 and import again.";
        }
        return "The XMI document must contain uml:Model in the " + UML + " namespace.";
    }

    private static Document parse(byte[] bytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            ErrorHandler errors = new DefaultHandler() {
                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            };
            builder.setErrorHandler(errors);
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException | SAXException | IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("The XMI document is invalid or unsafe to parse.", exception);
        }
    }

    private final class Context {

        private final Element model;
        private final Element extension;
        private final Map<String, Integer> losses = new TreeMap<>();
        private final List<XmiReadingResult.Disagreement> disagreements = new ArrayList<>();
        private final Map<String, String> typeNames = new HashMap<>();
        private final Map<String, Element> extensionElements = new HashMap<>();
        private final Map<String, ExtensionConnector> extensionConnectors = new HashMap<>();
        private final Map<UUID, String> documentation = new LinkedHashMap<>();
        private final List<ParsedDiagram> parsedDiagrams = new ArrayList<>();

        private Context(Element model, Element extension) {
            this.model = model;
            this.extension = extension;
            if (extension != null) {
                Element elements = child(extension, "elements");
                for (Element element : children(elements, "element")) {
                    String id = xmi(element, "idref");
                    if (!id.isEmpty()) {
                        extensionElements.put(id, element);
                    }
                }
                Element connectors = child(extension, "connectors");
                for (Element connector : children(connectors, "connector")) {
                    String id = xmi(connector, "idref");
                    if (!id.isEmpty()) {
                        extensionConnectors.put(id, readExtensionConnector(connector));
                    }
                }
            }
        }

        private void indexTypes() {
            for (Element element : descendants(model, "packagedElement")) {
                indexType(element);
            }
            if (extension != null) {
                Element primitives = child(extension, "primitivetypes");
                for (Element element : descendants(primitives, "packagedElement")) {
                    indexType(element);
                }
            }
        }

        private void indexType(Element element) {
            if ("uml:PrimitiveType".equals(xmi(element, "type")) || isClassifierType(xmi(element, "type"))) {
                String id = xmi(element, "id");
                if (!id.isEmpty() && !value(element, "name", "").isBlank()) {
                    typeNames.putIfAbsent(id, element.getAttribute("name"));
                }
            }
        }

        private void readClassifiers(Element parent, UUID namespaceId, List<ClassifierResource> output) {
            int anonymous = 0;
            for (Element element : children(parent, "packagedElement")) {
                String type = xmi(element, "type");
                ClassifierKind kind = classifierKind(type, element.getAttribute("isAbstract"));
                if (kind == null) {
                    if (!"uml:PrimitiveType".equals(type) && !isRelationshipType(type)) {
                        loss("unsupported_classifier", 1);
                    }
                    continue;
                }
                String id = xmi(element, "id");
                UUID classifierId = id(element, "classifier:" + namespaceId + ":" + anonymous++ + ":"
                        + element.getAttribute("name"));
                Element extensionElement = extensionElements.get(id);
                List<AttributeResource> attributes = readAttributes(element, kind == ClassifierKind.ENUMERATION,
                        classifierId, extensionElement);
                List<OperationResource> operations = readOperations(element, classifierId, extensionElement);
                List<ConstraintResource> constraints = readConstraints(element);
                Element properties = child(extensionElement, "properties");
                output.add(new ClassifierResource(classifierId, element.getAttribute("name"), kind,
                        Boolean.parseBoolean(element.getAttribute("isAbstract")), namespaceId,
                        attributes, operations, constraints, null, null, null,
                        classifierStereotype(properties, kind)));

                String note = properties == null ? "" : properties.getAttribute("documentation");
                if (!note.isBlank()) {
                    documentation.put(classifierId, note);
                }
                readClassifiers(element, classifierId, output);
            }
        }

        private List<AttributeResource> readAttributes(Element owner, boolean enumeration, UUID ownerId,
                Element extensionElement) {
            List<AttributeResource> result = new ArrayList<>();
            Map<String, String> stereotypes = memberStereotypes(extensionElement, "attributes", "attribute");
            int ordinal = 0;
            for (Element attribute : children(owner, "ownedAttribute")) {
                UUID id = id(attribute, "attribute:" + ownerId + ":" + ordinal);
                ParsedMultiplicity multiplicity = multiplicity(attribute);
                TypeReference type = enumeration ? TypeReference.empty() : readType(attribute);
                Element defaultValue = child(attribute, "defaultValue");
                result.add(new AttributeResource(id, attribute.getAttribute("name"),
                        enumeration ? "LITERAL" : "ATTRIBUTE", visibility(attribute.getAttribute("visibility")),
                        multiplicity.kind(), defaultValue == null ? null : defaultValue.getAttribute("value"),
                        type.id(), type.name(), ordinal++, multiplicity.lowerBound(), multiplicity.upperBound(),
                        memberStereotype(stereotypes.get(xmi(attribute, "id")), AttributeStereotype.class,
                                "unsupported_attribute_stereotype"),
                        Boolean.parseBoolean(attribute.getAttribute("isStatic")),
                        Boolean.parseBoolean(attribute.getAttribute("isDerived"))));
            }
            return result;
        }

        private List<OperationResource> readOperations(Element owner, UUID ownerId, Element extensionElement) {
            List<OperationResource> result = new ArrayList<>();
            Map<String, String> stereotypes = memberStereotypes(extensionElement, "operations", "operation");
            int operationOrdinal = 0;
            for (Element operation : children(owner, "ownedOperation")) {
                List<ParameterResource> parameters = new ArrayList<>();
                UUID returnId = null;
                String returnName = null;
                int parameterOrdinal = 0;
                for (Element parameter : children(operation, "ownedParameter")) {
                    TypeReference type = readType(parameter);
                    String rawDirection = parameter.getAttribute("direction");
                    String direction = rawDirection.isBlank() ? rawDirection
                            : rawDirection.toUpperCase(Locale.ROOT);
                    if ("return".equalsIgnoreCase(direction)) {
                        returnId = type.id();
                        returnName = type.name();
                    } else {
                        parameters.add(new ParameterResource(id(parameter,
                                "parameter:" + ownerId + ":" + operationOrdinal + ":" + parameterOrdinal),
                                parameter.getAttribute("name"), direction,
                                type.id(), type.name(), parameterOrdinal++));
                    }
                }
                Element operationType = child(operation, "type");
                if (operationType != null) {
                    TypeReference directReturn = readTypeElement(operationType);
                    if (returnId == null && returnName == null) {
                        returnId = directReturn.id();
                        returnName = directReturn.name();
                    }
                }
                result.add(new OperationResource(id(operation,
                        "operation:" + ownerId + ":" + operationOrdinal), operation.getAttribute("name"),
                        visibility(operation.getAttribute("visibility")),
                        Boolean.parseBoolean(operation.getAttribute("isAbstract")),
                        Boolean.parseBoolean(operation.getAttribute("isStatic")), returnId, returnName,
                        operationOrdinal++, parameters,
                        memberStereotype(stereotypes.get(xmi(operation, "id")), OperationStereotype.class,
                                "unsupported_operation_stereotype")));
            }
            return result;
        }

        /** The `stereotype` of every member the extension lists under {@code container}, keyed by XMI id. */
        private Map<String, String> memberStereotypes(Element extensionElement, String container, String memberName) {
            Map<String, String> result = new HashMap<>();
            for (Element member : children(child(extensionElement, container), memberName)) {
                Element stereotype = child(member, "stereotype");
                String written = stereotype == null ? "" : stereotype.getAttribute("stereotype").trim();
                if (!written.isEmpty()) {
                    result.put(xmi(member, "idref"), written);
                }
            }
            return result;
        }

        /** An attribute or operation stereotype from the fixed list; an unknown name is dropped with a loss entry. */
        private <E extends Enum<E>> String memberStereotype(String written, Class<E> list, String lossName) {
            if (written == null) {
                return null;
            }
            String canonical = Stereotypes.canonical(list, written);
            if (canonical == null) {
                loss(lossName, 1);
            }
            return canonical;
        }

        /** The classifier stereotype of the extension properties; unknown names and non-class kinds are dropped. */
        private String classifierStereotype(Element properties, ClassifierKind kind) {
            String written = properties == null ? "" : properties.getAttribute("stereotype").trim();
            if (written.isEmpty()) {
                return null;
            }
            // EA marks an interface or enumeration with its own kind keyword as the stereotype; that
            // is the classifier kind we already model, not an applied stereotype, so it is no loss.
            if (!Stereotypes.classifierMayCarryOne(kind) && written.equalsIgnoreCase(kind.name())) {
                return null;
            }
            String canonical = Stereotypes.canonical(ClassifierStereotype.class, written);
            if (canonical == null || !Stereotypes.classifierMayCarryOne(kind)) {
                loss("unsupported_classifier_stereotype", 1);
                return null;
            }
            return canonical;
        }

        private List<ConstraintResource> readConstraints(Element owner) {
            List<ConstraintResource> result = new ArrayList<>();
            for (Element rule : children(owner, "ownedRule")) {
                Element specification = child(rule, "specification");
                Element body = child(specification, "body");
                String expression = body == null ? null : body.getTextContent();
                if (expression == null) {
                    loss("unresolved_constraint_expression", 1);
                }
                result.add(new ConstraintResource(id(rule, "constraint:" + result.size()), expression, null));
                loss("constraint_severity_not_representable", 1);
            }
            return result;
        }

        /**
         * UML comments (EA notes): an {@code ownedComment} with its text in {@code body} and the elements it
         * annotates as {@code annotatedElement} idrefs. A reference to anything that is not a classifier or a
         * relationship of the document is dropped with a loss entry, as is an empty body; a longer body is cut.
         */
        private List<NoteResource> readNotes(List<ClassifierResource> classifiers,
                List<RelationshipResource> relationships) {
            Set<UUID> classifierIds = new LinkedHashSet<>();
            classifiers.forEach(classifier -> classifierIds.add(classifier.id()));
            Set<UUID> relationshipIds = new LinkedHashSet<>();
            relationships.forEach(relationship -> relationshipIds.add(relationship.id()));
            List<NoteResource> result = new ArrayList<>();
            Set<UUID> seen = new LinkedHashSet<>();
            for (Element comment : descendants(model, "ownedComment")) {
                String type = xmi(comment, "type");
                if (!type.isEmpty() && !"uml:Comment".equals(type)) {
                    continue;
                }
                String body = comment.hasAttribute("body") ? comment.getAttribute("body")
                        : child(comment, "body") == null ? "" : child(comment, "body").getTextContent();
                if (body == null || body.isBlank()) {
                    loss("empty_note", 1);
                    continue;
                }
                if (body.length() > 2000) {
                    body = body.substring(0, 2000);
                    loss("note_body_truncated", 1);
                }
                UUID id = id(comment, "note:" + result.size());
                if (!seen.add(id)) {
                    loss("duplicate_note", 1);
                    continue;
                }
                Set<NoteLinkResource> links = new LinkedHashSet<>();
                for (Element reference : children(comment, "annotatedElement")) {
                    UUID target = uuid(xmi(reference, "idref"));
                    if (classifierIds.contains(target)) {
                        links.add(new NoteLinkResource(TargetType.CLASSIFIER, target));
                    } else if (relationshipIds.contains(target)) {
                        links.add(new NoteLinkResource(TargetType.RELATIONSHIP, target));
                    } else {
                        loss("unresolved_note_reference", 1);
                    }
                }
                result.add(new NoteResource(id, body, List.copyOf(links)));
            }
            return result;
        }

        private List<RelationshipResource> readRelationships() {
            List<RelationshipResource> result = new ArrayList<>();
            Map<String, Element> endsById = new HashMap<>();
            for (Element end : descendants(model, "ownedEnd")) {
                String id = xmi(end, "id");
                if (!id.isEmpty()) {
                    endsById.put(id, end);
                }
            }
            for (Element association : descendants(model, "packagedElement")) {
                if (!"uml:Association".equals(xmi(association, "type"))) {
                    continue;
                }
                String rawId = xmi(association, "id");
                UUID relationshipId = id(association, "association:" + result.size());
                List<Element> semanticEnds = new ArrayList<>();
                for (Element memberEnd : children(association, "memberEnd")) {
                    Element ownedEnd = endsById.get(xmi(memberEnd, "idref"));
                    if (ownedEnd != null) {
                        semanticEnds.add(ownedEnd);
                    } else {
                        loss("unresolved_relationship_end", 1);
                    }
                }
                if (semanticEnds.isEmpty()) {
                    semanticEnds.addAll(children(association, "ownedEnd"));
                }
                ExtensionConnector extensionConnector = extensionConnectors.get(rawId);
                List<RelationshipEndResource> ends = relationshipEnds(relationshipId, semanticEnds,
                        extensionConnector, rawId, MultiplicityKind.RANGE);
                String kind = ends.stream().anyMatch(end -> "composite".equals(end.aggregationKind()))
                        ? RelationshipKind.COMPOSITION.name()
                        : ends.stream().anyMatch(end -> "shared".equals(end.aggregationKind()))
                                ? RelationshipKind.AGGREGATION.name() : RelationshipKind.ASSOCIATION.name();
                result.add(new RelationshipResource(relationshipId, kind,
                        nonempty(association.getAttribute("name"), extensionConnector == null ? null : extensionConnector.name()),
                        null, associationClass(extensionConnector), ends, readConstraints(association)));
            }
            for (Element generalization : descendants(model, "generalization")) {
                String rawId = xmi(generalization, "id");
                UUID relationshipId = id(generalization, "generalization:" + result.size());
                Element specific = parentClassifier(generalization);
                String specificRef = specific == null ? "" : xmi(specific, "id");
                String generalRef = generalization.getAttribute("general");
                List<Element> semanticEnds = new ArrayList<>();
                ExtensionConnector connector = extensionConnectors.get(rawId);
                List<RelationshipEndResource> ends = directedEnds(relationshipId, specificRef, generalRef,
                        semanticEnds, connector, rawId);
                result.add(new RelationshipResource(relationshipId, RelationshipKind.GENERALIZATION.name(),
                        null, ends, readConstraints(generalization)));
            }
            for (Element relationship : descendants(model, "packagedElement")) {
                String type = xmi(relationship, "type");
                RelationshipKind kind = switch (type) {
                    case "uml:Realization" -> RelationshipKind.INTERFACE_REALIZATION;
                    case "uml:Dependency" -> RelationshipKind.DEPENDENCY;
                    default -> null;
                };
                if (kind == null) {
                    continue;
                }
                String rawId = xmi(relationship, "id");
                ExtensionConnector connector = extensionConnectors.get(rawId);
                List<RelationshipEndResource> ends = directedEnds(id(relationship,
                        type + ":" + result.size()), relationship.getAttribute("client"),
                        relationship.getAttribute("supplier"), List.of(), connector, rawId);
                result.add(new RelationshipResource(id(relationship, type + ":" + result.size()),
                        kind.name(), relationship.getAttribute("name"),
                        kind == RelationshipKind.DEPENDENCY ? dependencyStereotype(connector) : null, ends,
                        readConstraints(relationship)));
            }
            return result;
        }

        private List<RelationshipEndResource> directedEnds(UUID relationshipId, String firstRef, String secondRef,
                List<Element> semanticEnds, ExtensionConnector connector, String subjectId) {
            List<RelationshipEndResource> result = relationshipEnds(relationshipId, semanticEnds, connector,
                    subjectId, MultiplicityKind.EXACTLY_ONE);
            if (!result.isEmpty()) {
                return result;
            }
            return List.of(directedEnd(relationshipId, firstRef, 1), directedEnd(relationshipId, secondRef, 2));
        }

        private RelationshipEndResource directedEnd(UUID relationshipId, String classifierRef, int ordinal) {
            UUID classifierId = uuid(classifierRef);
            return new RelationshipEndResource(namedUuid("end:" + relationshipId + ":" + ordinal), classifierId,
                    null, MultiplicityKind.EXACTLY_ONE, NavigationKind.UNSPECIFIED, "none", (short) ordinal);
        }

        private List<RelationshipEndResource> relationshipEnds(UUID relationshipId, List<Element> semanticEnds,
                ExtensionConnector connector, String subjectId, MultiplicityKind defaultMultiplicity) {
            List<SemanticEnd> parsed = new ArrayList<>();
            for (Element end : semanticEnds) {
                Element type = child(end, "type");
                String classifierRef = type == null ? "" : xmi(type, "idref");
                if (classifierRef.isEmpty()) {
                    loss("unresolved_relationship_end", 1);
                }
                ParsedMultiplicity endMultiplicity = child(end, "lowerValue") == null
                        || child(end, "upperValue") == null ? null : multiplicity(end);
                parsed.add(new SemanticEnd(xmi(end, "id"), classifierRef, end.getAttribute("name"),
                        endMultiplicity == null ? null : endMultiplicity.kind(),
                        bound(end, "lowerValue"), bound(end, "upperValue"), end.getAttribute("aggregation"),
                        endMultiplicity == null ? null : endMultiplicity.lowerBound(),
                        endMultiplicity == null ? null : endMultiplicity.upperBound(),
                        Boolean.parseBoolean(end.getAttribute("isOrdered")),
                        Boolean.parseBoolean(end.getAttribute("isUnique"))));
            }
            List<RelationshipEndResource> result = new ArrayList<>();
            Set<SemanticEnd> used = new LinkedHashSet<>();
            List<ExtensionEnd> extensionEnds = new ArrayList<>();
            if (connector != null) {
                if (connector.source() != null) {
                    extensionEnds.add(connector.source());
                }
                if (connector.target() != null) {
                    extensionEnds.add(connector.target());
                }
            }
            int ordinal = 1;
            for (ExtensionEnd extensionEnd : extensionEnds) {
                if (extensionEnd == null || extensionEnd.classifierRef().isEmpty()) {
                    continue;
                }
                SemanticEnd semantic = parsed.stream().filter(end -> !used.contains(end)
                        && end.classifierRef().equals(extensionEnd.classifierRef())
                        && (extensionEnd.role() == null || Objects.equals(end.role(), extensionEnd.role())))
                        .findFirst().orElseGet(() -> parsed.stream().filter(end -> !used.contains(end)
                                && end.classifierRef().equals(extensionEnd.classifierRef())).findFirst().orElse(null));
                if (semantic == null && extensionEnd.role() != null) {
                    semantic = parsed.stream().filter(end -> !used.contains(end)
                            && Objects.equals(end.role(), extensionEnd.role())).findFirst().orElse(null);
                    if (semantic != null && !semantic.classifierRef().isEmpty()) {
                        disagreement("relationship_end_classifier", subjectId + ":" + ordinal,
                                semantic.classifierRef(), extensionEnd.classifierRef());
                    }
                }
                if (semantic != null) {
                    used.add(semantic);
                }
                result.add(mergeEnd(relationshipId, ordinal++, semantic, extensionEnd, subjectId,
                        defaultMultiplicity));
            }
            for (SemanticEnd semantic : parsed) {
                if (!used.contains(semantic)) {
                    result.add(mergeEnd(relationshipId, ordinal++, semantic, null, subjectId,
                            defaultMultiplicity));
                }
            }
            return result;
        }

        private RelationshipEndResource mergeEnd(UUID relationshipId, int ordinal, SemanticEnd semantic,
                ExtensionEnd extensionEnd, String subjectId, MultiplicityKind defaultMultiplicity) {
            String classifierRef = semantic != null && !semantic.classifierRef().isEmpty()
                    ? semantic.classifierRef() : extensionEnd == null ? "" : extensionEnd.classifierRef();
            if (classifierRef.isEmpty()) {
                loss("unresolved_relationship_end", 1);
            }
            String role = extensionEnd != null && extensionEnd.role() != null
                    ? extensionEnd.role() : semantic == null ? null : semantic.role();
            MultiplicityKind semanticMultiplicity = semantic == null ? null : semantic.multiplicity();
            MultiplicityKind extensionMultiplicity = extensionEnd == null ? null
                    : parseMultiplicity(extensionEnd.multiplicity());
            if (extensionEnd != null && extensionEnd.multiplicity() != null && extensionMultiplicity == null) {
                loss("unsupported_extension_multiplicity", 1);
            }
            if (semanticMultiplicity != null && extensionMultiplicity != null
                    && semanticMultiplicity != extensionMultiplicity) {
                disagreement("multiplicity", subjectId + ":" + ordinal,
                        lower(semantic) + ".." + upper(semantic), extensionEnd.multiplicity());
            }
            MultiplicityKind multiplicity = semanticMultiplicity != null ? semanticMultiplicity
                    : extensionMultiplicity != null ? extensionMultiplicity : defaultMultiplicity;
            Integer lowerBound = null;
            Integer upperBound = null;
            if (multiplicity == MultiplicityKind.RANGE) {
                if (semantic != null && semantic.multiplicity() == MultiplicityKind.RANGE) {
                    // Bounds (or the 0..* fallback with its loss) were settled when the semantic end was parsed.
                    lowerBound = semantic.lowerBound();
                    upperBound = semantic.upperBound();
                } else {
                    // A RANGE that only came from a default or an extension has no numeric bounds to keep.
                    loss("unsupported_multiplicity", 1);
                    lowerBound = 0;
                }
            }
            NavigationKind navigation = extensionEnd == null ? NavigationKind.UNSPECIFIED
                    : extensionEnd.navigability();
            String aggregation = semantic != null && !semantic.aggregation().isBlank()
                    ? semantic.aggregation().toLowerCase(Locale.ROOT)
                    : extensionEnd == null || extensionEnd.aggregation() == null
                            ? "none" : extensionEnd.aggregation().toLowerCase(Locale.ROOT);
            UUID endId = semantic != null && !semantic.id().isEmpty() ? uuid(semantic.id())
                    : namedUuid("end:" + relationshipId + ":" + ordinal);
            return new RelationshipEndResource(endId, uuid(classifierRef), role, multiplicity,
                    navigation, aggregation, (short) ordinal, lowerBound, upperBound,
                    semantic != null && semantic.isOrdered(), semantic != null && semantic.isUnique());
        }

        private List<XmiReadingResult.Diagram> readDiagrams() {
            if (extension == null) {
                return List.of();
            }
            Element container = child(extension, "diagrams");
            List<XmiReadingResult.Diagram> result = new ArrayList<>();
            for (Element diagram : children(container, "diagram")) {
                UUID diagramId = id(diagram, "diagram:" + result.size());
                Element properties = child(diagram, "properties");
                String name = properties == null ? diagram.getAttribute("name") : properties.getAttribute("name");
                Element placements = child(diagram, "elements");
                List<XmiReadingResult.FigurePlacement> figures = new ArrayList<>();
                List<XmiReadingResult.ConnectorPlacement> connectors = new ArrayList<>();
                List<Element> diagramPlacements = children(placements, "element");
                for (Element placement : diagramPlacements) {
                    Map<String, String> geometry = pairs(placement.getAttribute("geometry"));
                    if (geometry.keySet().containsAll(Set.of("Left", "Top", "Right", "Bottom"))) {
                        readFigure(placement, geometry, figures);
                    } else if (!geometry.keySet().containsAll(Set.of("SX", "SY", "EX", "EY"))) {
                        loss("unrecognized_diagram_geometry", 1);
                    }
                }
                for (Element placement : diagramPlacements) {
                    Map<String, String> geometry = pairs(placement.getAttribute("geometry"));
                    if (geometry.keySet().containsAll(Set.of("SX", "SY", "EX", "EY"))) {
                        connectors.add(readConnectorPlacement(placement, geometry, diagramId, figures));
                    }
                }
                ParsedDiagram parsed = new ParsedDiagram(diagramId, name, figures, connectors);
                parsedDiagrams.add(parsed);
                result.add(parsed.toResource(result.isEmpty()));
            }
            return result;
        }

        private void readFigure(Element placement, Map<String, String> geometry,
                List<XmiReadingResult.FigurePlacement> figures) {
            String subject = placement.getAttribute("subject");
            if (subject.isEmpty()) {
                loss("diagram_figure_subject_missing", 1);
                return;
            }
            try {
                String duid = pairs(placement.getAttribute("style")).get("DUID");
                if (duid == null || duid.isBlank()) {
                    loss("diagram_figure_duid_missing", 1);
                }
                figures.add(new XmiReadingResult.FigurePlacement(subject, uuid(subject), duid,
                        integer(geometry, "Left"), integer(geometry, "Top"), integer(geometry, "Right"),
                        integer(geometry, "Bottom"), parseInt(placement.getAttribute("seqno"), 0)));
            } catch (NumberFormatException exception) {
                loss("invalid_diagram_figure_geometry", 1);
            }
        }

        private XmiReadingResult.ConnectorPlacement readConnectorPlacement(Element placement,
                Map<String, String> geometry, UUID diagramId, List<XmiReadingResult.FigurePlacement> figures) {
            String subject = placement.getAttribute("subject");
            if (subject.isEmpty()) {
                loss("diagram_connector_subject_missing", 1);
                subject = "diagram-connector:" + diagramId + ":" + figures.size();
            }
            Map<String, String> style = pairs(placement.getAttribute("style"));
            String sourceDuid = style.get("SOID");
            String targetDuid = style.get("EOID");
            if (sourceDuid == null || targetDuid == null) {
                loss("diagram_connector_anchors_missing", 1);
            } else {
                Set<String> duids = new LinkedHashSet<>();
                figures.stream().map(XmiReadingResult.FigurePlacement::duid).filter(Objects::nonNull)
                        .forEach(duids::add);
                if (!duids.contains(sourceDuid)) {
                    loss("diagram_connector_anchor_unresolved", 1);
                }
                if (!duids.contains(targetDuid)) {
                    loss("diagram_connector_anchor_unresolved", 1);
                }
            }
            String path = geometry.get("Path");
            if (path != null && !path.isEmpty()) {
                loss("connector_path_not_interpreted", 1);
            }
            Set<String> known = Set.of("SX", "SY", "EX", "EY", "Path");
            if (geometry.keySet().stream().anyMatch(key -> !known.contains(key))) {
                loss("connector_vendor_geometry_not_interpreted", 1);
            }
            ExtensionConnector extensionConnector = extensionConnectors.get(subject);
            XmiReadingResult.ConnectorEnd source = connectorEnd(extensionConnector == null ? null
                    : extensionConnector.source());
            XmiReadingResult.ConnectorEnd target = connectorEnd(extensionConnector == null ? null
                    : extensionConnector.target());
            return new XmiReadingResult.ConnectorPlacement(subject, uuid(subject), style.get("Mode"),
                    sourceDuid, targetDuid, path, source, target);
        }

        private XmiReadingResult.ConnectorEnd connectorEnd(ExtensionEnd end) {
            if (end == null || end.classifierRef().isEmpty()) {
                return null;
            }
            return new XmiReadingResult.ConnectorEnd(end.classifierRef(), uuid(end.classifierRef()), end.role(),
                    end.visibility(), end.multiplicity(), end.navigability());
        }

        private List<ViewResource> views() {
            List<ViewResource> result = new ArrayList<>();
            for (int index = 0; index < parsedDiagrams.size(); index++) {
                ParsedDiagram diagram = parsedDiagrams.get(index);
                List<GeometryResource> geometries = new ArrayList<>();
                int ordinal = 0;
                for (XmiReadingResult.FigurePlacement figure : diagram.figures()) {
                    geometries.add(new GeometryResource(namedUuid("geometry:" + diagram.id() + ":" + ordinal++),
                            TargetType.CLASSIFIER, figure.classifierId(), figure.left(), figure.top(),
                            figure.right() - figure.left(), figure.bottom() - figure.top(), figure.sequence()));
                }
                result.add(new ViewResource(diagram.id(), diagram.name(), index == 0, geometries,
                        List.<ConnectorPathResource>of()));
            }
            return result;
        }

        private long revision() {
            if (extension == null) {
                return 0;
            }
            Element diagrams = child(extension, "diagrams");
            Element diagram = child(diagrams, "diagram");
            Element project = child(diagram, "project");
            return parseLong(project == null ? "" : project.getAttribute("version"), 0);
        }

        private XmiReadingResult result(ModelResource model, List<XmiReadingResult.Diagram> diagrams) {
            countExtensionLayout();
            List<XmiReadingResult.Loss> report = losses.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(entry -> new XmiReadingResult.Loss(entry.getKey(), entry.getValue())).toList();
            return new XmiReadingResult(model, diagrams, documentation, disagreements, report);
        }

        private void countExtensionLayout() {
            if (extension == null) {
                return;
            }
            for (String name : List.of("coords", "bounds", "containment")) {
                long count = descendants(extension, name).size();
                if (count > 0) {
                    loss("attribute_layout_" + name, Math.toIntExact(count));
                }
            }
        }

        /**
         * Reads lowerValue/upperValue. A numeric RANGE keeps its bounds (upper -1 means unbounded); an
         * unreadable or inconsistent one is reported as a loss and falls back to the neutral 0..* RANGE so the
         * persisted bounds always satisfy the model's invariants.
         */
        private ParsedMultiplicity multiplicity(Element element) {
            Element lower = child(element, "lowerValue");
            Element upper = child(element, "upperValue");
            if (lower == null || upper == null) {
                loss("unsupported_multiplicity", 1);
                return ParsedMultiplicity.FALLBACK_RANGE;
            }
            String low = lower.getAttribute("value");
            String high = upper.getAttribute("value");
            String upperType = xmi(upper, "type");
            boolean validUpperType = "-1".equals(high)
                    ? "uml:LiteralUnlimitedNatural".equals(upperType)
                    : "uml:LiteralInteger".equals(upperType);
            if (!"uml:LiteralInteger".equals(xmi(lower, "type")) || !validUpperType) {
                loss("unsupported_multiplicity_encoding", 1);
                return ParsedMultiplicity.FALLBACK_RANGE;
            }
            MultiplicityKind result = boundsMultiplicity(low, high);
            if (result != MultiplicityKind.RANGE) {
                return new ParsedMultiplicity(result, null, null);
            }
            Integer lowerBound = parseBound(low);
            Integer upperBound = "-1".equals(high) ? null : parseBound(high);
            boolean valid = lowerBound != null && lowerBound >= 0
                    && ("-1".equals(high) || (upperBound != null && upperBound >= Math.max(lowerBound, 1)));
            if (!valid) {
                loss("unsupported_multiplicity", 1);
                return ParsedMultiplicity.FALLBACK_RANGE;
            }
            return new ParsedMultiplicity(MultiplicityKind.RANGE, lowerBound, upperBound);
        }

        private String bound(Element owner, String childName) {
            Element value = child(owner, childName);
            return value == null ? "" : value.getAttribute("value");
        }

        private TypeReference readType(Element owner) {
            String attributeType = owner.getAttribute("type");
            if (!attributeType.isEmpty()) {
                return typeReference(attributeType);
            }
            Element type = child(owner, "type");
            if (type != null) {
                return readTypeElement(type);
            }
            loss("unresolved_type_reference", 1);
            return TypeReference.empty();
        }

        private TypeReference readTypeElement(Element element) {
            String idref = xmi(element, "idref");
            if (idref.isEmpty()) {
                loss("unresolved_type_reference", 1);
                return TypeReference.empty();
            }
            return typeReference(idref);
        }

        private TypeReference typeReference(String id) {
            String name = typeNames.get(id);
            if (name == null) {
                loss("unresolved_type_reference", 1);
            }
            return new TypeReference(uuid(id), name);
        }

        /** The stereotype of a dependency, when the connector names one of the UML dependency stereotypes. */
        private String dependencyStereotype(ExtensionConnector connector) {
            String written = connector == null || connector.stereotype() == null ? "" : connector.stereotype().trim();
            if (written.isEmpty()) {
                return null;
            }
            try {
                return DependencyStereotype.valueOf(written.toUpperCase(java.util.Locale.ROOT)).name();
            } catch (IllegalArgumentException exception) {
                loss("unsupported_relationship_stereotype", 1);
                return null;
            }
        }

        /** The class an association names as its own, when the connector carries one. */
        private UUID associationClass(ExtensionConnector connector) {
            String written = connector == null || connector.associationClass() == null
                    ? "" : connector.associationClass().trim();
            return written.isEmpty() ? null : uuid(written);
        }

        private ExtensionConnector readExtensionConnector(Element connector) {
            Element properties = child(connector, "properties");
            return new ExtensionConnector(xmi(connector, "idref"), connector.getAttribute("name"),
                    properties == null ? "" : properties.getAttribute("stereotype"),
                    properties == null ? "" : properties.getAttribute("associationClass"),
                    readExtensionEnd(child(connector, "source")), readExtensionEnd(child(connector, "target")));
        }

        private ExtensionEnd readExtensionEnd(Element element) {
            if (element == null) {
                return null;
            }
            Element role = child(element, "role");
            Element type = child(element, "type");
            Element modifiers = child(element, "modifiers");
            Element style = child(element, "style");
            Map<String, String> styleValues = pairs(style == null ? "" : style.getAttribute("value"));
            NavigationKind modifierNavigation = parseNavigability(modifiers == null
                    ? null : modifiers.getAttribute("isNavigable"));
            String styleNavigation = styleValues.get("Navigable");
            NavigationKind navigation = styleNavigation == null || "Unspecified".equalsIgnoreCase(styleNavigation)
                    ? NavigationKind.UNSPECIFIED
                    : parseNavigability(styleNavigation);
            if (navigation == null) {
                navigation = modifierNavigation == null ? NavigationKind.UNSPECIFIED : modifierNavigation;
                loss("unsupported_navigability", 1);
            } else if (navigation != NavigationKind.UNSPECIFIED && modifierNavigation != null
                    && navigation != modifierNavigation) {
                disagreement("navigability", connectorId(element), modifierNavigation.name(), navigation.name());
            } else if (navigation == NavigationKind.UNSPECIFIED && styleNavigation == null
                    && modifierNavigation != null) {
                navigation = modifierNavigation;
            }
            String visibility = role == null ? null : visibility(role.getAttribute("visibility"));
            return new ExtensionEnd(xmi(element, "idref"), role == null ? null : nullable(role.getAttribute("name")),
                    visibility, type == null ? null : nullable(type.getAttribute("multiplicity")),
                    type == null ? null : nullable(type.getAttribute("aggregation")), navigation);
        }

        private String connectorId(Element end) {
            Node parent = end.getParentNode();
            return parent instanceof Element connector && "connector".equals(connector.getLocalName())
                    ? xmi(connector, "idref") : xmi(end, "idref");
        }

        private UUID id(Element element, String fallback) {
            String raw = xmi(element, "id");
            return raw.isEmpty() ? namedUuid("anonymous:" + fallback) : uuid(raw);
        }

        private void disagreement(String kind, String subject, String semantic, String extensionValue) {
            disagreements.add(new XmiReadingResult.Disagreement(kind, subject, semantic, extensionValue));
            loss(kind + "_disagreement", 1);
        }

        private void loss(String name, int count) {
            if (count > 0) {
                losses.merge(name, count, Integer::sum);
            }
        }

        private Element parentClassifier(Element element) {
            Node parent = element.getParentNode();
            while (parent instanceof Element candidate) {
                if ("packagedElement".equals(candidate.getLocalName()) && isClassifierType(xmi(candidate, "type"))) {
                    return candidate;
                }
                parent = parent.getParentNode();
            }
            return null;
        }

        private int integer(Map<String, String> values, String key) {
            return Integer.parseInt(values.get(key));
        }

    }

    private static Element first(Document document, String namespace, String name) {
        var nodes = document.getElementsByTagNameNS(namespace, name);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Element child : children(parent, name)) {
            return child;
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        if (parent == null) {
            return List.of();
        }
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static List<Element> descendants(Element parent, String name) {
        if (parent == null) {
            return List.of();
        }
        List<Element> result = new ArrayList<>();
        var nodes = parent.getElementsByTagNameNS("*", name);
        for (int index = 0; index < nodes.getLength(); index++) {
            result.add((Element) nodes.item(index));
        }
        return result;
    }

    private static String xmi(Element element, String name) {
        return element == null ? "" : element.getAttributeNS(XMI, name);
    }

    private static String value(Element element, String name, String fallback) {
        String value = element.getAttribute(name);
        return value.isEmpty() ? fallback : value;
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nonempty(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static String visibility(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static Map<String, String> pairs(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : value.split(";", -1)) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                result.put(part.substring(0, equals).trim(), part.substring(equals + 1).trim());
            }
        }
        return result;
    }

    private static ClassifierKind classifierKind(String type, String abstractValue) {
        return switch (type) {
            case "uml:Class" -> Boolean.parseBoolean(abstractValue)
                    ? ClassifierKind.ABSTRACT_CLASS : ClassifierKind.CLASS;
            case "uml:Interface" -> ClassifierKind.INTERFACE;
            case "uml:Enumeration" -> ClassifierKind.ENUMERATION;
            case "uml:Package" -> ClassifierKind.PACKAGE;
            default -> null;
        };
    }

    private static boolean isClassifierType(String type) {
        return "uml:Class".equals(type) || "uml:Interface".equals(type)
                || "uml:Enumeration".equals(type) || "uml:Package".equals(type);
    }

    private static boolean isRelationshipType(String type) {
        return "uml:Association".equals(type) || "uml:Realization".equals(type)
                || "uml:Dependency".equals(type);
    }

    private static UUID uuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return namedUuid("missing-id");
        }
        try {
            if (raw.startsWith("EAID_")) {
                return XmiIdCodec.classifierUuid(raw);
            }
            if (raw.startsWith("EAPK_")) {
                return XmiIdCodec.packageUuid(raw);
            }
        } catch (IllegalArgumentException ignored) {
            // Foreign EA ids are opaque identities, not malformed UUIDs.
        }
        return namedUuid("id:" + raw);
    }

    private static UUID namedUuid(String value) {
        return UUID.nameUUIDFromBytes((UUID_NAMESPACE + value).getBytes(StandardCharsets.UTF_8));
    }

    private static String lower(SemanticEnd end) {
        return end == null ? "" : end.lower();
    }

    private static String upper(SemanticEnd end) {
        return end == null ? "" : end.upper();
    }

    private static Integer parseBound(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record ParsedMultiplicity(MultiplicityKind kind, Integer lowerBound, Integer upperBound) {
        private static final ParsedMultiplicity FALLBACK_RANGE = new ParsedMultiplicity(MultiplicityKind.RANGE, 0, null);
    }

    private static MultiplicityKind boundsMultiplicity(String lower, String upper) {
        if ("1".equals(lower) && "1".equals(upper)) {
            return MultiplicityKind.EXACTLY_ONE;
        }
        if ("0".equals(lower) && "1".equals(upper)) {
            return MultiplicityKind.ZERO_OR_ONE;
        }
        if ("1".equals(lower) && "-1".equals(upper)) {
            return MultiplicityKind.ONE_OR_MANY;
        }
        if ("0".equals(lower) && "-1".equals(upper)) {
            return MultiplicityKind.ZERO_OR_MANY;
        }
        return MultiplicityKind.RANGE;
    }

    private static MultiplicityKind parseMultiplicity(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim()) {
            case "1", "1..1" -> MultiplicityKind.EXACTLY_ONE;
            case "0..1" -> MultiplicityKind.ZERO_OR_ONE;
            case "1..*" -> MultiplicityKind.ONE_OR_MANY;
            case "0..*" -> MultiplicityKind.ZERO_OR_MANY;
            default -> null;
        };
    }

    private static NavigationKind parseNavigability(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "navigable" -> NavigationKind.NAVIGABLE;
            case "false", "notnavigable", "not_navigable" -> NavigationKind.NOT_NAVIGABLE;
            case "unspecified" -> NavigationKind.UNSPECIFIED;
            default -> null;
        };
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private record TypeReference(UUID id, String name) {
        private static TypeReference empty() {
            return new TypeReference(null, null);
        }
    }

    private record SemanticEnd(String id, String classifierRef, String role,
            MultiplicityKind multiplicity, String lower, String upper, String aggregation, Integer lowerBound,
            Integer upperBound, boolean isOrdered, boolean isUnique) {
    }

    private record ExtensionEnd(String classifierRef, String role, String visibility,
            String multiplicity, String aggregation, NavigationKind navigability) {
    }

    private record ExtensionConnector(String id, String name, String stereotype, String associationClass,
            ExtensionEnd source, ExtensionEnd target) {
    }

    private record ParsedDiagram(UUID id, String name, List<XmiReadingResult.FigurePlacement> figures,
            List<XmiReadingResult.ConnectorPlacement> connectors) {
        private ParsedDiagram {
            figures = List.copyOf(figures);
            connectors = List.copyOf(connectors);
        }

        private XmiReadingResult.Diagram toResource(boolean isDefault) {
            return new XmiReadingResult.Diagram(id, name, isDefault, figures, connectors);
        }
    }
}
