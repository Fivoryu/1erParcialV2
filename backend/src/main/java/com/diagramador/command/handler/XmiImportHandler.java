package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.diagramador.api.ModelResource;
import com.diagramador.command.CommandHandler;
import com.diagramador.command.CommandValidationException;
import com.diagramador.command.ErrorCode;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.ExchangeDirection;
import com.diagramador.domain.ExchangeStatus;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.AttributeStereotype;
import com.diagramador.domain.ClassifierStereotype;
import com.diagramador.domain.DependencyStereotype;
import com.diagramador.domain.OperationStereotype;
import com.diagramador.domain.Stereotypes;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.SeverityKind;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlConstraint;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UmlNote;
import com.diagramador.domain.UmlOperation;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.domain.XmiExchange;
import com.diagramador.domain.XmiLossItem;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.ConnectorPathRepository;
import com.diagramador.persistence.DiagramViewRepository;
import com.diagramador.persistence.ElementGeometryRepository;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.TypeReferenceRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlNoteRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import com.diagramador.persistence.XmiExchangeRepository;
import com.diagramador.persistence.XmiLossItemRepository;
import com.diagramador.xmi.XmiImportIssue;
import com.diagramador.xmi.XmiImportMode;
import com.diagramador.xmi.XmiImportService;
import com.diagramador.xmi.XmiImportValidator;
import com.diagramador.xmi.XmiReadingResult;
import com.diagramador.xmi.XmiSemanticGate;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Applies a complete, validated XMI graph as one command transaction. */
@Component
public class XmiImportHandler implements CommandHandler.AddAction {

    private static final Set<String> PAYLOAD_FIELDS = Set.of("mode", "profile", "checksum", "readingResult");

    private final XmiImportValidator validator = new XmiImportValidator();
    private final XmiSemanticGate semanticGate = new XmiSemanticGate();
    private final ObjectMapper objectMapper;
    private final ClassifierRepository classifierRepository;
    private final UmlAttributeRepository attributeRepository;
    private final UmlOperationRepository operationRepository;
    private final OperationParameterRepository parameterRepository;
    private final TypeReferenceRepository typeReferenceRepository;
    private final UmlConstraintRepository constraintRepository;
    private final UmlRelationshipRepository relationshipRepository;
    private final RelationshipEndRepository relationshipEndRepository;
    private final DiagramViewRepository viewRepository;
    private final ElementGeometryRepository geometryRepository;
    private final ConnectorPathRepository pathRepository;
    private final EntityManager entityManager;
    private final XmiExchangeRepository exchangeRepository;
    private final XmiLossItemRepository lossItemRepository;
    private final UmlNoteRepository noteRepository;

    public XmiImportHandler(ObjectMapper objectMapper, ClassifierRepository classifierRepository,
            UmlAttributeRepository attributeRepository, UmlOperationRepository operationRepository,
            OperationParameterRepository parameterRepository, TypeReferenceRepository typeReferenceRepository,
            UmlConstraintRepository constraintRepository, UmlRelationshipRepository relationshipRepository,
            RelationshipEndRepository relationshipEndRepository, DiagramViewRepository viewRepository,
            ElementGeometryRepository geometryRepository, ConnectorPathRepository pathRepository,
            EntityManager entityManager, XmiExchangeRepository exchangeRepository,
            XmiLossItemRepository lossItemRepository, UmlNoteRepository noteRepository) {
        this.objectMapper = objectMapper;
        this.classifierRepository = classifierRepository;
        this.attributeRepository = attributeRepository;
        this.operationRepository = operationRepository;
        this.parameterRepository = parameterRepository;
        this.typeReferenceRepository = typeReferenceRepository;
        this.constraintRepository = constraintRepository;
        this.relationshipRepository = relationshipRepository;
        this.relationshipEndRepository = relationshipEndRepository;
        this.viewRepository = viewRepository;
        this.geometryRepository = geometryRepository;
        this.pathRepository = pathRepository;
        this.entityManager = entityManager;
        this.exchangeRepository = exchangeRepository;
        this.lossItemRepository = lossItemRepository;
        this.noteRepository = noteRepository;
    }

    @Override
    public String type() {
        return XmiImportService.COMMAND_TYPE;
    }

    @Override
    public Outcome apply(Context context) {
        String stage = "payload validation";
        try {
            JsonNode payload = payload(context);
            XmiImportMode mode = mode(payload);
            String profile = text(payload, "profile");
            String checksum = text(payload, "checksum");
            if (!checksum.matches("(?i)[0-9a-f]{64}")) {
                invalid("Payload field 'checksum' must be a SHA-256 hex digest");
            }
            XmiReadingResult reading = scopeConflictingIdentities(context.model(), readingResult(payload));
            if (reading == null || reading.model() == null) {
                invalid("A parsed XMI model is required");
            }
            rejectFirstBlocker(validator.validate(reading, mode,
                    mode == XmiImportMode.AGREGAR ? existingModel(context.model())
                            : XmiImportValidator.ExistingModel.EMPTY));
            rejectFirstSemanticBlocker(semanticGate.issues(reading));

            ExistingRows oldRows = mode == XmiImportMode.REEMPLAZAR ? existingRows(context.model()) : null;
            List<DeletedTarget> deletedTargets = oldRows == null ? List.of() : deletedTargets(oldRows, reading);
            if (mode == XmiImportMode.AGREGAR) {
                rejectIdCollisions(reading.model());
            } else {
                stage = "replacement deletion";
                deleteExistingModel(context.model(), oldRows);
            }

            stage = "classifier insertion";
            Map<UUID, Classifier> classifiers = persistClassifiers(context.model(), reading.model());
            Map<UUID, TypeReference> typeReferences = new HashMap<>();
            stage = "attribute and operation insertion";
            persistAttributesAndOperations(context.model(), reading.model(), classifiers, typeReferences);
            stage = "relationship insertion";
            persistRelationships(context.model(), reading.model(), classifiers, typeReferences);
            stage = "note insertion";
            persistNotes(context, reading.model(), classifiers);
            stage = "diagram insertion";
            persistViews(context, reading.model(), classifiers, mode);
            stage = "exchange and loss recording";
            persistExchange(context, reading, profile, checksum);
            return new Outcome(deletedTargets);
        } catch (CommandValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("XMI import failed during " + stage + ": " + exception.getMessage(),
                    exception);
        }
    }

    /**
     * Keeps source UUIDs when available, but scopes the whole import to the destination model if another project
     * already owns one of its classifier UUIDs. Database primary keys are global even though XMI ids are external.
     */
    public XmiReadingResult scopeConflictingIdentities(UmlModel target, XmiReadingResult reading) {
        boolean conflict = reading.model().classifiers().stream()
                .map(ModelResource.ClassifierResource::id)
                .map(classifierRepository::findById)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::orElseThrow)
                .anyMatch(classifier -> !classifier.getModel().getId().equals(target.getId()));
        if (!conflict) {
            return reading;
        }

        ModelResource source = reading.model();
        Map<UUID, UUID> classifierIds = scopedIds(source.classifiers().stream()
                .map(ModelResource.ClassifierResource::id).toList(), target.getId());
        Map<UUID, UUID> attributeIds = scopedIds(source.classifiers().stream()
                .flatMap(classifier -> classifier.attributes().stream())
                .map(ModelResource.AttributeResource::id).toList(), target.getId());
        Map<UUID, UUID> operationIds = scopedIds(source.classifiers().stream()
                .flatMap(classifier -> classifier.operations().stream())
                .map(ModelResource.OperationResource::id).toList(), target.getId());
        Map<UUID, UUID> parameterIds = scopedIds(source.classifiers().stream()
                .flatMap(classifier -> classifier.operations().stream())
                .flatMap(operation -> operation.parameters().stream())
                .map(ModelResource.ParameterResource::id).toList(), target.getId());
        Map<UUID, UUID> relationshipIds = scopedIds(source.relationships().stream()
                .map(ModelResource.RelationshipResource::id).toList(), target.getId());
        Map<UUID, UUID> endIds = scopedIds(source.relationships().stream()
                .flatMap(relationship -> relationship.ends().stream())
                .map(ModelResource.RelationshipEndResource::id).toList(), target.getId());
        List<ModelResource.ConstraintResource> sourceConstraints = source.classifiers().stream()
                .flatMap(classifier -> classifier.constraints().stream())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        source.relationships().forEach(relationship -> sourceConstraints.addAll(relationship.constraints()));
        Map<UUID, UUID> constraintIds = scopedIds(sourceConstraints.stream()
                .map(ModelResource.ConstraintResource::id).toList(), target.getId());
        Map<UUID, UUID> viewIds = scopedIds(source.views().stream().map(ModelResource.ViewResource::id).toList(),
                target.getId());
        Map<UUID, UUID> geometryIds = scopedIds(source.views().stream()
                .flatMap(view -> view.elementGeometry().stream()).map(ModelResource.GeometryResource::id).toList(),
                target.getId());
        Map<UUID, UUID> pathIds = scopedIds(source.views().stream()
                .flatMap(view -> view.connectorPath().stream()).map(ModelResource.ConnectorPathResource::id).toList(),
                target.getId());
        Map<UUID, UUID> noteIds = scopedIds(source.notes().stream().map(ModelResource.NoteResource::id).toList(),
                target.getId());

        List<ModelResource.ClassifierResource> classifiers = source.classifiers().stream().map(classifier -> {
            List<ModelResource.AttributeResource> attributes = classifier.attributes().stream().map(attribute ->
                    new ModelResource.AttributeResource(mapped(attributeIds, attribute.id()), attribute.name(),
                            attribute.kind(), attribute.visibility(), attribute.multiplicity(), attribute.defaultValue(),
                            mapped(classifierIds, attribute.typeReferenceId()), attribute.type(), attribute.ordinal(),
                            attribute.lowerBound(), attribute.upperBound(), attribute.stereotype(),
                            attribute.isStatic(), attribute.isDerived()))
                    .toList();
            List<ModelResource.OperationResource> operations = classifier.operations().stream().map(operation -> {
                List<ModelResource.ParameterResource> parameters = operation.parameters().stream().map(parameter ->
                        new ModelResource.ParameterResource(mapped(parameterIds, parameter.id()), parameter.name(),
                                parameter.direction(), mapped(classifierIds, parameter.typeReferenceId()),
                                parameter.type(), parameter.ordinal())).toList();
                return new ModelResource.OperationResource(mapped(operationIds, operation.id()), operation.name(),
                        operation.visibility(), operation.isAbstract(), operation.isStatic(),
                        mapped(classifierIds, operation.returnTypeReferenceId()), operation.returnType(),
                        operation.ordinal(), parameters, operation.stereotype());
            }).toList();
            List<ModelResource.ConstraintResource> constraints = classifier.constraints().stream().map(constraint ->
                    new ModelResource.ConstraintResource(mapped(constraintIds, constraint.id()),
                            constraint.expression(), constraint.severity())).toList();
            return new ModelResource.ClassifierResource(mapped(classifierIds, classifier.id()), classifier.name(),
                    classifier.kind(), classifier.isAbstract(), mapped(classifierIds, classifier.namespaceId()),
                    attributes, operations, constraints, null, null, null, classifier.stereotype());
        }).toList();
        List<ModelResource.RelationshipResource> relationships = source.relationships().stream().map(relationship -> {
            List<ModelResource.RelationshipEndResource> ends = relationship.ends().stream().map(end ->
                    new ModelResource.RelationshipEndResource(mapped(endIds, end.id()),
                            mapped(classifierIds, end.classifierId()), end.role(), end.multiplicity(),
                            end.navigability(), end.aggregationKind(), end.ordinal(),
                            end.lowerBound(), end.upperBound(), end.isOrdered(), end.isUnique())).toList();
            List<ModelResource.ConstraintResource> constraints = relationship.constraints().stream().map(constraint ->
                    new ModelResource.ConstraintResource(mapped(constraintIds, constraint.id()),
                            constraint.expression(), constraint.severity())).toList();
            return new ModelResource.RelationshipResource(mapped(relationshipIds, relationship.id()),
                    relationship.kind(), relationship.name(), relationship.stereotype(),
                    mapped(classifierIds, relationship.associationClassId()), ends, constraints);
        }).toList();
        List<ModelResource.ViewResource> views = source.views().stream().map(view -> {
            List<ModelResource.GeometryResource> geometry = view.elementGeometry().stream().map(item ->
                    new ModelResource.GeometryResource(mapped(geometryIds, item.id()), item.targetType(),
                            item.targetType() == TargetType.CLASSIFIER ? mapped(classifierIds, item.targetId())
                                    : item.targetId(),
                            item.x(), item.y(), item.w(), item.h(), item.zOrder())).toList();
            List<ModelResource.ConnectorPathResource> paths = view.connectorPath().stream().map(path ->
                    new ModelResource.ConnectorPathResource(mapped(pathIds, path.id()),
                            mapped(relationshipIds, path.relationshipId()), path.ordinal(), path.x(), path.y()))
                    .toList();
            return new ModelResource.ViewResource(mapped(viewIds, view.id()), view.name(), view.isDefault(),
                    geometry, paths);
        }).toList();
        List<ModelResource.NoteResource> notes = source.notes().stream().map(note ->
                new ModelResource.NoteResource(mapped(noteIds, note.id()), note.body(),
                        note.annotatedElements().stream().map(link -> new ModelResource.NoteLinkResource(
                                link.targetType(), link.targetType() == TargetType.CLASSIFIER
                                        ? mapped(classifierIds, link.targetId())
                                        : mapped(relationshipIds, link.targetId()))).toList())).toList();
        ModelResource scoped = new ModelResource(source.id(), source.projectId(), source.revision(), source.versionId(),
                views, classifiers, relationships, List.of(), notes);
        Map<UUID, String> documentation = new LinkedHashMap<>();
        reading.documentation().forEach((id, text) -> documentation.put(mapped(classifierIds, id), text));
        List<XmiReadingResult.Diagram> diagrams = reading.diagrams().stream().map(diagram -> {
            List<XmiReadingResult.FigurePlacement> figures = diagram.figures().stream().map(figure ->
                    new XmiReadingResult.FigurePlacement(figure.subjectId(), mapped(classifierIds, figure.classifierId()),
                            figure.duid(), figure.left(), figure.top(), figure.right(), figure.bottom(), figure.sequence()))
                    .toList();
            List<XmiReadingResult.ConnectorPlacement> connectors = diagram.connectors().stream().map(connector ->
                    new XmiReadingResult.ConnectorPlacement(connector.subjectId(),
                            mapped(relationshipIds, connector.relationshipId()), connector.mode(),
                            connector.sourceDuid(), connector.targetDuid(), connector.path(),
                            scopedEnd(connector.source(), classifierIds), scopedEnd(connector.target(), classifierIds)))
                    .toList();
            return new XmiReadingResult.Diagram(mapped(viewIds, diagram.id()), diagram.name(), diagram.isDefault(),
                    figures, connectors);
        }).toList();
        return new XmiReadingResult(scoped, diagrams, documentation, reading.disagreements(), reading.losses());
    }

    /** The exact tombstone count is supplied to CommandService's cascade confirmation gate. */
    public int countDeletedTargets(UmlModel model, XmiReadingResult reading) {
        return deletedTargets(existingRows(model), reading).size();
    }

    private Map<UUID, UUID> scopedIds(List<UUID> ids, UUID targetModelId) {
        Map<UUID, UUID> result = new LinkedHashMap<>();
        for (UUID id : ids) {
            if (id != null) {
                result.put(id, UUID.nameUUIDFromBytes(("xmi-import:" + targetModelId + ":" + id)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
        return result;
    }

    /** Bounds persist only for RANGE (the table CHECK demands it); a RANGE without a lower bound reads as 0..*. */
    private static Integer rangeLower(MultiplicityKind multiplicity, Integer lowerBound) {
        if (multiplicity != MultiplicityKind.RANGE) {
            return null;
        }
        return lowerBound == null ? 0 : lowerBound;
    }

    private static Integer rangeUpper(MultiplicityKind multiplicity, Integer upperBound) {
        return multiplicity == MultiplicityKind.RANGE ? upperBound : null;
    }

    private UUID mapped(Map<UUID, UUID> ids, UUID id) {
        return id == null ? null : ids.getOrDefault(id, id);
    }

    private XmiReadingResult.ConnectorEnd scopedEnd(XmiReadingResult.ConnectorEnd end, Map<UUID, UUID> classifierIds) {
        return end == null ? null : new XmiReadingResult.ConnectorEnd(end.classifierIdRef(),
                mapped(classifierIds, end.classifierId()), end.role(), end.visibility(), end.multiplicity(),
                end.navigability());
    }

    private JsonNode payload(Context context) {
        JsonNode payload = objectMapper.valueToTree(context.envelope().payload());
        if (payload == null || !payload.isObject()) {
            invalid("Payload must be a JSON object");
        }
        Set<String> unknown = new HashSet<>();
        payload.fieldNames().forEachRemaining(field -> {
            if (!PAYLOAD_FIELDS.contains(field)) {
                unknown.add(field);
            }
        });
        if (!unknown.isEmpty()) {
            invalid("Payload contains unsupported field(s): " + unknown);
        }
        for (String required : PAYLOAD_FIELDS) {
            if (!payload.hasNonNull(required)) {
                invalid("Payload field '" + required + "' is required");
            }
        }
        return payload;
    }

    private XmiImportMode mode(JsonNode payload) {
        try {
            return XmiImportMode.valueOf(text(payload, "mode"));
        } catch (IllegalArgumentException exception) {
            invalid("Payload field 'mode' must be REEMPLAZAR or AGREGAR");
            return null;
        }
    }

    private String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            invalid("Payload field '" + field + "' must be a non-blank string");
        }
        return value.asText();
    }

    private XmiReadingResult readingResult(JsonNode payload) {
        try {
            return objectMapper.treeToValue(payload.get("readingResult"), XmiReadingResult.class);
        } catch (Exception exception) {
            invalid("Payload field 'readingResult' is invalid");
            return null;
        }
    }

    /** The handler rejects with the first blocker, exactly as the checks it shares with the dry run order them. */
    private void rejectFirstBlocker(List<XmiImportIssue> issues) {
        issues.stream().filter(issue -> issue.severity() == XmiImportIssue.Severity.BLOCKER).findFirst()
                .ifPresent(issue -> {
                    throw new CommandValidationException(issue.rejectionCode(), issue.rejectionMessage());
                });
    }

    /** Same verdict and wording as the import service's dry run, so every route into the handler ends clean. */
    private void rejectFirstSemanticBlocker(List<XmiImportIssue> issues) {
        issues.stream().filter(issue -> issue.severity() == XmiImportIssue.Severity.BLOCKER).findFirst()
                .ifPresent(issue -> {
                    throw new CommandValidationException(issue.rejectionCode(),
                            "La importación no quedaría limpia: " + issue.rejectionMessage());
                });
    }

    /** The stored names AGREGAR can collide with; also read by the import service for its dry run. */
    public XmiImportValidator.ExistingModel existingModel(UmlModel model) {
        List<XmiImportValidator.ExistingClassifier> classifiers = classifierRepository.findByModelId(model.getId())
                .stream().map(item -> new XmiImportValidator.ExistingClassifier(item.getId(),
                        item.getNamespace() == null ? null : item.getNamespace().getId(), item.getName())).toList();
        Set<String> viewNames = new HashSet<>();
        viewRepository.findByModelId(model.getId()).forEach(view -> viewNames.add(view.getName()));
        return new XmiImportValidator.ExistingModel(classifiers, viewNames);
    }

    private void rejectIdCollisions(ModelResource model) {
        for (ModelResource.ClassifierResource classifier : model.classifiers()) {
            unavailable(classifierRepository.existsById(classifier.id()), classifier.id(), "classifier");
            classifier.attributes().forEach(item -> unavailable(attributeRepository.existsById(item.id()), item.id(), "attribute"));
            classifier.operations().forEach(item -> {
                unavailable(operationRepository.existsById(item.id()), item.id(), "operation");
                item.parameters().forEach(parameter -> unavailable(parameterRepository.existsById(parameter.id()),
                        parameter.id(), "parameter"));
            });
            classifier.constraints().forEach(item -> {
                if (item.expression() != null) {
                    unavailable(constraintRepository.existsById(item.id()), item.id(), "constraint");
                }
            });
        }
        for (ModelResource.RelationshipResource relationship : model.relationships()) {
            unavailable(relationshipRepository.existsById(relationship.id()), relationship.id(), "relationship");
            relationship.ends().forEach(end -> unavailable(relationshipEndRepository.existsById(end.id()),
                    end.id(), "relationship end"));
            relationship.constraints().forEach(item -> {
                if (item.expression() != null) {
                    unavailable(constraintRepository.existsById(item.id()), item.id(), "constraint");
                }
            });
        }
        model.notes().forEach(note -> unavailable(noteRepository.existsById(note.id()), note.id(), "note"));
        ModelResource.ViewResource view = model.views().get(0);
        unavailable(viewRepository.existsById(view.id()), view.id(), "diagram view");
        view.elementGeometry().forEach(item -> unavailable(geometryRepository.existsById(item.id()), item.id(), "geometry"));
        view.connectorPath().forEach(item -> unavailable(pathRepository.existsById(item.id()), item.id(), "connector path"));
    }

    private void unavailable(boolean exists, UUID id, String kind) {
        if (exists) {
            precondition("The imported " + kind + " id " + id + " already exists in this model");
        }
    }

    private ExistingRows existingRows(UmlModel model) {
        List<Classifier> classifiers = classifierRepository.findByModelId(model.getId());
        List<UmlAttribute> attributes = new ArrayList<>();
        List<UmlOperation> operations = new ArrayList<>();
        List<OperationParameter> parameters = new ArrayList<>();
        for (Classifier classifier : classifiers) {
            attributes.addAll(attributeRepository.findByClassifierId(classifier.getId()));
            List<UmlOperation> ownedOperations = operationRepository.findByClassifierId(classifier.getId());
            operations.addAll(ownedOperations);
            for (UmlOperation operation : ownedOperations) {
                parameters.addAll(parameterRepository.findByOperationId(operation.getId()));
            }
        }
        List<UmlRelationship> relationships = relationshipRepository.findByModelId(model.getId());
        List<RelationshipEnd> ends = new ArrayList<>();
        for (UmlRelationship relationship : relationships) {
            ends.addAll(relationshipEndRepository.findByRelationshipId(relationship.getId()));
        }
        return new ExistingRows(classifiers, attributes, operations, parameters,
                typeReferenceRepository.findByModelId(model.getId()), constraintRepository.findByModelId(model.getId()),
                relationships, ends, viewRepository.findByModelId(model.getId()),
                noteRepository.findByModelId(model.getId()));
    }

    private List<DeletedTarget> deletedTargets(ExistingRows oldRows, XmiReadingResult reading) {
        Set<DeletedTarget> retained = importedTargets(reading);
        return oldRows.targets().stream().filter(target -> !retained.contains(target)).toList();
    }

    private Set<DeletedTarget> importedTargets(XmiReadingResult reading) {
        Set<DeletedTarget> targets = new LinkedHashSet<>();
        ModelResource model = reading.model();
        for (ModelResource.ClassifierResource classifier : model.classifiers()) {
            targets.add(new DeletedTarget(TargetType.CLASSIFIER, classifier.id()));
            classifier.attributes().forEach(item -> targets.add(new DeletedTarget(TargetType.ATTRIBUTE, item.id())));
            classifier.operations().forEach(item -> {
                targets.add(new DeletedTarget(TargetType.OPERATION, item.id()));
                item.parameters().forEach(parameter -> targets.add(
                        new DeletedTarget(TargetType.PARAMETER, parameter.id())));
            });
            classifier.constraints().stream().filter(item -> item.expression() != null).forEach(item ->
                    targets.add(new DeletedTarget(TargetType.CONSTRAINT, item.id())));
        }
        for (ModelResource.RelationshipResource relationship : model.relationships()) {
            targets.add(new DeletedTarget(TargetType.RELATIONSHIP, relationship.id()));
            relationship.ends().forEach(end -> targets.add(new DeletedTarget(TargetType.RELATIONSHIP_END, end.id())));
            relationship.constraints().stream().filter(item -> item.expression() != null).forEach(item ->
                    targets.add(new DeletedTarget(TargetType.CONSTRAINT, item.id())));
        }
        model.views().forEach(view -> targets.add(new DeletedTarget(TargetType.DIAGRAM_VIEW, view.id())));
        model.notes().forEach(note -> targets.add(new DeletedTarget(TargetType.NOTE, note.id())));
        return targets;
    }

    private void deleteExistingModel(UmlModel model, ExistingRows rows) {
        for (DiagramView view : rows.views()) {
            geometryRepository.deleteAll(geometryRepository.findByViewId(view.getId()));
            pathRepository.deleteAll(pathRepository.findByViewId(view.getId()));
        }
        constraintRepository.deleteAll(rows.constraints());
        relationshipEndRepository.deleteAll(rows.ends());
        relationshipRepository.deleteAll(rows.relationships());
        parameterRepository.deleteAll(rows.parameters());
        attributeRepository.deleteAll(rows.attributes());
        operationRepository.deleteAll(rows.operations());
        typeReferenceRepository.deleteAll(rows.typeReferences());
        viewRepository.deleteAll(rows.views());
        noteRepository.deleteAll(rows.notes());
        classifierRepository.deleteAll(rows.classifiers());
    }

    private Map<UUID, Classifier> persistClassifiers(UmlModel model, ModelResource imported) {
        Map<UUID, ModelResource.ClassifierResource> pending = new LinkedHashMap<>();
        imported.classifiers().forEach(classifier -> pending.put(classifier.id(), classifier));
        Map<UUID, Classifier> persisted = new LinkedHashMap<>();
        while (!pending.isEmpty()) {
            boolean progressed = false;
            Iterator<Map.Entry<UUID, ModelResource.ClassifierResource>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                ModelResource.ClassifierResource resource = iterator.next().getValue();
                if (resource.namespaceId() != null && !persisted.containsKey(resource.namespaceId())) {
                    continue;
                }
                Classifier namespace = resource.namespaceId() == null ? null : persisted.get(resource.namespaceId());
                ClassifierKind kind = resource.kind();
                boolean isAbstract = kind == ClassifierKind.ABSTRACT_CLASS
                        || (kind == ClassifierKind.CLASS && resource.isAbstract());
                entityManager.createNativeQuery("""
                        INSERT INTO diagrammer.classifier
                            (classifier_id, model_id, namespace_id, kind, name, is_abstract, stereotype)
                        VALUES (:id, :modelId, :namespaceId, CAST(:kind AS diagrammer.classifier_kind), :name, :abstract,
                                :stereotype)
                        """)
                        .setParameter("id", resource.id())
                        .setParameter("modelId", model.getId())
                        .setParameter("namespaceId", namespace == null ? null : namespace.getId())
                        .setParameter("kind", isAbstract ? ClassifierKind.ABSTRACT_CLASS.name() : kind.name())
                        .setParameter("name", resource.name())
                        .setParameter("abstract", isAbstract)
                        .setParameter("stereotype", Stereotypes.classifierMayCarryOne(kind)
                                ? Stereotypes.canonical(ClassifierStereotype.class, resource.stereotype()) : null)
                        .executeUpdate();
                Classifier classifier = classifierRepository.findById(resource.id())
                        .orElseThrow(() -> new IllegalStateException("Imported classifier could not be reloaded"));
                persisted.put(resource.id(), classifier);
                iterator.remove();
                progressed = true;
            }
            if (!progressed) {
                precondition("Imported classifier namespaces contain a cycle");
            }
        }
        return persisted;
    }

    private void persistAttributesAndOperations(UmlModel model, ModelResource imported,
            Map<UUID, Classifier> classifiers, Map<UUID, TypeReference> typeReferences) {
        for (ModelResource.ClassifierResource classifierResource : imported.classifiers()) {
            Classifier classifier = classifiers.get(classifierResource.id());
            for (ModelResource.AttributeResource resource : classifierResource.attributes()) {
                TypeReference type = typeReference(model, resource.typeReferenceId(), resource.type(), classifiers,
                        typeReferences);
                MultiplicityKind multiplicity = resource.multiplicity() == null
                        ? MultiplicityKind.EXACTLY_ONE : resource.multiplicity();
                entityManager.createNativeQuery("""
                        INSERT INTO diagrammer.uml_attribute
                            (attribute_id, classifier_id, type_reference_id, name, kind, visibility,
                             multiplicity, lower_bound, upper_bound, default_value, ordinal, stereotype,
                             is_static, is_derived)
                        VALUES (:id, :classifierId, :typeId, :name, :kind, :visibility,
                                CAST(:multiplicity AS diagrammer.multiplicity_kind), :lowerBound, :upperBound,
                                :defaultValue, :ordinal, :stereotype, :isStatic, :isDerived)
                        """)
                        .setParameter("id", resource.id())
                        .setParameter("classifierId", classifier.getId())
                        .setParameter("typeId", type == null ? null : type.getId())
                        .setParameter("name", resource.name())
                        .setParameter("kind", resource.kind())
                        .setParameter("visibility", resource.visibility())
                        .setParameter("multiplicity", multiplicity.name())
                        .setParameter("lowerBound", rangeLower(multiplicity, resource.lowerBound()))
                        .setParameter("upperBound", rangeUpper(multiplicity, resource.upperBound()))
                        .setParameter("defaultValue", resource.defaultValue())
                        .setParameter("ordinal", resource.ordinal())
                        .setParameter("stereotype", Stereotypes.canonical(AttributeStereotype.class,
                                resource.stereotype()))
                        .setParameter("isStatic", resource.isStatic())
                        .setParameter("isDerived", resource.isDerived())
                        .executeUpdate();
            }
            for (ModelResource.OperationResource resource : classifierResource.operations()) {
                TypeReference returnType = typeReference(model, resource.returnTypeReferenceId(), resource.returnType(),
                        classifiers, typeReferences);
                entityManager.createNativeQuery("""
                        INSERT INTO diagrammer.uml_operation
                            (operation_id, classifier_id, name, return_type_reference_id, visibility,
                             is_abstract, is_static, ordinal, stereotype)
                        VALUES (:id, :classifierId, :name, :returnTypeId, :visibility,
                                :abstract, :static, :ordinal, :stereotype)
                        """)
                        .setParameter("id", resource.id())
                        .setParameter("classifierId", classifier.getId())
                        .setParameter("name", resource.name())
                        .setParameter("returnTypeId", returnType == null ? null : returnType.getId())
                        .setParameter("visibility", resource.visibility())
                        .setParameter("abstract", resource.isAbstract())
                        .setParameter("static", resource.isStatic())
                        .setParameter("ordinal", resource.ordinal())
                        .setParameter("stereotype", Stereotypes.canonical(OperationStereotype.class,
                                resource.stereotype()))
                        .executeUpdate();
                UmlOperation operation = operationRepository.findById(resource.id())
                        .orElseThrow(() -> new IllegalStateException("Imported operation could not be reloaded"));
                for (ModelResource.ParameterResource parameter : resource.parameters()) {
                    TypeReference parameterType = typeReference(model, parameter.typeReferenceId(), parameter.type(),
                            classifiers, typeReferences);
                    entityManager.createNativeQuery("""
                            INSERT INTO diagrammer.operation_parameter
                                (parameter_id, operation_id, type_reference_id, name, direction, ordinal)
                            VALUES (:id, :operationId, :typeId, :name, :direction, :ordinal)
                            """)
                            .setParameter("id", parameter.id())
                            .setParameter("operationId", operation.getId())
                            .setParameter("typeId", parameterType == null ? null : parameterType.getId())
                            .setParameter("name", parameter.name())
                            .setParameter("direction", parameter.direction())
                            .setParameter("ordinal", parameter.ordinal())
                            .executeUpdate();
                }
            }
            persistConstraints(model, TargetType.CLASSIFIER, classifierResource.id(), classifierResource.constraints());
        }
    }

    private TypeReference typeReference(UmlModel model, UUID sourceId, String typeName,
            Map<UUID, Classifier> classifiers, Map<UUID, TypeReference> cache) {
        if (sourceId == null || typeName == null || typeName.isBlank()) {
            return null;
        }
        TypeReference cached = cache.get(sourceId);
        if (cached != null) {
            return cached;
        }
        Classifier classifier = classifiers.get(sourceId);
        if (classifier == null) {
            List<Classifier> byName = classifiers.values().stream()
                    .filter(candidate -> candidate.getName().equals(typeName)).toList();
            if (byName.size() > 1) {
                precondition("Type name '" + typeName + "' is ambiguous in the imported model");
            }
            if (byName.size() == 1) {
                classifier = byName.get(0);
            }
        }
        TypeReference created = classifier == null
                ? TypeReference.forPrimitive(model, typeName)
                : TypeReference.forClassifier(model, classifier);
        entityManager.persist(created);
        entityManager.flush();
        cache.put(sourceId, created);
        return created;
    }

    private void persistRelationships(UmlModel model, ModelResource imported, Map<UUID, Classifier> classifiers,
            Map<UUID, TypeReference> typeReferences) {
        Set<UUID> takenAssociationClasses = new HashSet<>();
        for (ModelResource.RelationshipResource resource : imported.relationships()) {
            RelationshipKind kind = RelationshipKind.valueOf(resource.kind().toUpperCase(Locale.ROOT));
            entityManagerInsertRelationship(resource, model.getId(), kind,
                    importedAssociationClass(resource, kind, classifiers, takenAssociationClasses));
            UmlRelationship relationship = relationshipRepository.findById(resource.id())
                    .orElseThrow(() -> new IllegalStateException("Imported relationship could not be reloaded"));
            for (ModelResource.RelationshipEndResource end : resource.ends()) {
                insertRelationshipEnd(end, relationship.getId(), classifiers.get(end.classifierId()));
            }
            persistConstraints(model, TargetType.RELATIONSHIP, resource.id(), resource.constraints());
        }
    }

    private void entityManagerInsertRelationship(ModelResource.RelationshipResource resource, UUID modelId,
            RelationshipKind kind, UUID associationClassId) {
        entityManager.createNativeQuery("""
                INSERT INTO diagrammer.uml_relationship (relationship_id, model_id, kind, name, stereotype, association_class_id)
                VALUES (:id, :modelId, CAST(:kind AS diagrammer.relationship_kind), :name, :stereotype, :associationClassId)
                """)
                .setParameter("id", resource.id())
                .setParameter("modelId", modelId)
                .setParameter("kind", kind.name())
                .setParameter("name", resource.name())
                .setParameter("stereotype", importedStereotype(resource, kind))
                .setParameter("associationClassId", associationClassId)
                .executeUpdate();
    }

    /**
     * An imported stereotype only counts on a dependency and only when it is one of the UML dependency
     * stereotypes. The reading arrives as a command payload, so anything else is dropped instead of stored.
     */
    /**
     * An imported association class only counts on an association, aggregation or composition, when it is a class
     * of the import that is not one of the ends and no other relationship has taken it. Anything else is dropped.
     */
    private static UUID importedAssociationClass(ModelResource.RelationshipResource resource, RelationshipKind kind,
            Map<UUID, Classifier> classifiers, Set<UUID> taken) {
        UUID id = resource.associationClassId();
        Classifier classifier = id == null ? null : classifiers.get(id);
        boolean association = kind == RelationshipKind.ASSOCIATION || kind == RelationshipKind.AGGREGATION
                || kind == RelationshipKind.COMPOSITION;
        boolean isClass = classifier != null
                && (classifier.getKind() == ClassifierKind.CLASS || classifier.getKind() == ClassifierKind.ABSTRACT_CLASS);
        boolean isEnd = resource.ends().stream().anyMatch(end -> end.classifierId().equals(id));
        return association && isClass && !isEnd && taken.add(id) ? id : null;
    }

    private static String importedStereotype(ModelResource.RelationshipResource resource, RelationshipKind kind) {
        if (kind != RelationshipKind.DEPENDENCY || resource.stereotype() == null) {
            return null;
        }
        try {
            return DependencyStereotype.valueOf(resource.stereotype().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void insertRelationshipEnd(ModelResource.RelationshipEndResource end, UUID relationshipId,
            Classifier classifier) {
        entityManager.createNativeQuery("""
                INSERT INTO diagrammer.relationship_end
                    (relationship_end_id, relationship_id, classifier_id, ordinal, role, multiplicity,
                     lower_bound, upper_bound, navigability, aggregation_kind, is_ordered, is_unique)
                VALUES (:id, :relationshipId, :classifierId, :ordinal, :role,
                        CAST(:multiplicity AS diagrammer.multiplicity_kind), :lowerBound, :upperBound,
                        CAST(:navigability AS diagrammer.navigation_kind), :aggregation, :isOrdered, :isUnique)
                """)
                .setParameter("id", end.id())
                .setParameter("relationshipId", relationshipId)
                .setParameter("classifierId", classifier.getId())
                .setParameter("ordinal", end.ordinal())
                .setParameter("role", end.role())
                .setParameter("multiplicity", (end.multiplicity() == null
                        ? MultiplicityKind.EXACTLY_ONE : end.multiplicity()).name())
                .setParameter("lowerBound", rangeLower(end.multiplicity(), end.lowerBound()))
                .setParameter("upperBound", rangeUpper(end.multiplicity(), end.upperBound()))
                .setParameter("navigability", (end.navigability() == null
                        ? NavigationKind.UNSPECIFIED : end.navigability()).name())
                .setParameter("aggregation", end.aggregationKind() == null ? "NONE"
                        : end.aggregationKind().toUpperCase(Locale.ROOT))
                .setParameter("isOrdered", end.isOrdered())
                .setParameter("isUnique", end.isUnique())
                .executeUpdate();
    }

    private void persistConstraints(UmlModel model, TargetType ownerType, UUID ownerId,
            List<ModelResource.ConstraintResource> resources) {
        for (ModelResource.ConstraintResource resource : resources) {
            if (resource.expression() == null || resource.expression().isBlank()) {
                continue;
            }
            SeverityKind severity = resource.severity() == null ? SeverityKind.INFO
                    : SeverityKind.valueOf(resource.severity().toUpperCase(Locale.ROOT));
            entityManager.createNativeQuery("""
                    INSERT INTO diagrammer.uml_constraint
                        (constraint_id, model_id, owner_type, owner_id, expression, severity)
                    VALUES (:id, :modelId, CAST(:ownerType AS diagrammer.target_type), :ownerId,
                            :expression, CAST(:severity AS diagrammer.severity_kind))
                    """)
                    .setParameter("id", resource.id())
                    .setParameter("modelId", model.getId())
                    .setParameter("ownerType", ownerType.name())
                    .setParameter("ownerId", ownerId)
                    .setParameter("expression", resource.expression())
                    .setParameter("severity", severity.name())
                    .executeUpdate();
        }
    }

    /** Notes come after classifiers and relationships; a link whose element was not imported is dropped. */
    private void persistNotes(Context context, ModelResource imported, Map<UUID, Classifier> classifiers) {
        Set<UUID> relationshipIds = new HashSet<>();
        imported.relationships().forEach(relationship -> relationshipIds.add(relationship.id()));
        for (ModelResource.NoteResource note : imported.notes()) {
            entityManager.createNativeQuery("""
                    INSERT INTO diagrammer.uml_note (note_id, model_id, body, created_at)
                    VALUES (CAST(:id AS uuid), CAST(:modelId AS uuid), :body, now())
                    """)
                    .setParameter("id", note.id())
                    .setParameter("modelId", context.model().getId())
                    .setParameter("body", note.body())
                    .executeUpdate();
            Set<ModelResource.NoteLinkResource> inserted = new LinkedHashSet<>();
            for (ModelResource.NoteLinkResource link : note.annotatedElements()) {
                boolean present = link.targetType() == TargetType.CLASSIFIER
                        ? classifiers.containsKey(link.targetId())
                        : link.targetType() == TargetType.RELATIONSHIP && relationshipIds.contains(link.targetId());
                if (!present || !inserted.add(link)) {
                    continue;
                }
                entityManager.createNativeQuery("""
                        INSERT INTO diagrammer.uml_note_link (note_id, target_type, target_id)
                        VALUES (CAST(:noteId AS uuid), :targetType, CAST(:targetId AS uuid))
                        """)
                        .setParameter("noteId", note.id())
                        .setParameter("targetType", link.targetType().name())
                        .setParameter("targetId", link.targetId())
                        .executeUpdate();
            }
        }
    }

    private void persistViews(Context context, ModelResource imported, Map<UUID, Classifier> classifiers,
            XmiImportMode mode) {
        ModelResource.ViewResource resource = imported.views().get(0);
        boolean defaultView = mode == XmiImportMode.REEMPLAZAR || viewRepository.findByModelId(context.model().getId()).isEmpty();
        context.entityManager().createNativeQuery("""
                INSERT INTO diagrammer.diagram_view (view_id, model_id, name, view_kind, is_default)
                VALUES (:id, :modelId, :name, 'CLASS', :defaultView)
                """)
                .setParameter("id", resource.id())
                .setParameter("modelId", context.model().getId())
                .setParameter("name", resource.name())
                .setParameter("defaultView", defaultView)
                .executeUpdate();
        DiagramView view = viewRepository.findById(resource.id())
                .orElseThrow(() -> new IllegalStateException("Imported diagram view could not be reloaded"));
        for (ModelResource.GeometryResource geometry : resource.elementGeometry()) {
            entityManager.createNativeQuery("""
                    INSERT INTO diagrammer.element_geometry
                        (geometry_id, view_id, target_type, target_id, x, y, w, h, z_order)
                    VALUES (:id, :viewId, CAST(:targetType AS diagrammer.target_type), :targetId,
                            :x, :y, :w, :h, :zOrder)
                    """)
                    .setParameter("id", geometry.id())
                    .setParameter("viewId", view.getId())
                    .setParameter("targetType", geometry.targetType().name())
                    .setParameter("targetId", geometry.targetId())
                    .setParameter("x", geometry.x())
                    .setParameter("y", geometry.y())
                    .setParameter("w", geometry.w())
                    .setParameter("h", geometry.h())
                    .setParameter("zOrder", geometry.zOrder())
                    .executeUpdate();
        }
        for (ModelResource.ConnectorPathResource path : resource.connectorPath()) {
            entityManager.createNativeQuery("""
                    INSERT INTO diagrammer.connector_path
                        (path_id, view_id, relationship_id, ordinal, x, y)
                    VALUES (:id, :viewId, :relationshipId, :ordinal, :x, :y)
                    """)
                    .setParameter("id", path.id())
                    .setParameter("viewId", view.getId())
                    .setParameter("relationshipId", path.relationshipId())
                    .setParameter("ordinal", path.ordinal())
                    .setParameter("x", path.x())
                    .setParameter("y", path.y())
                    .executeUpdate();
        }
    }

    private void persistExchange(Context context, XmiReadingResult reading, String profile, String checksum) {
        XmiExchange exchange = XmiExchange.create(context.model(), ExchangeDirection.IMPORT, profile,
                context.envelope().baseRevision() + 1L, checksum, null);
        if (!reading.losses().isEmpty() || !reading.disagreements().isEmpty()) {
            exchange.setStatus(ExchangeStatus.PARTIAL);
        }
        exchangeRepository.saveAndFlush(exchange);
        List<XmiLossItem> items = reading.losses().stream()
                .map(loss -> exchange.addLossItem(SeverityKind.WARNING, null,
                        loss.name() + ": " + loss.count() + " item(s) could not be represented."))
                .toList();
        lossItemRepository.saveAllAndFlush(items);
    }

    private void invalid(String message) {
        throw new CommandValidationException(ErrorCode.INVALID_PAYLOAD, message);
    }

    private void precondition(String message) {
        throw new CommandValidationException(ErrorCode.PRECONDITION_FAILED, message);
    }

    private record ExistingRows(List<Classifier> classifiers, List<UmlAttribute> attributes,
            List<UmlOperation> operations, List<OperationParameter> parameters, List<TypeReference> typeReferences,
            List<UmlConstraint> constraints, List<UmlRelationship> relationships, List<RelationshipEnd> ends,
            List<DiagramView> views, List<UmlNote> notes) {

        private List<DeletedTarget> targets() {
            Set<DeletedTarget> targets = new LinkedHashSet<>();
            classifiers.forEach(item -> targets.add(new DeletedTarget(TargetType.CLASSIFIER, item.getId())));
            attributes.forEach(item -> targets.add(new DeletedTarget(TargetType.ATTRIBUTE, item.getId())));
            operations.forEach(item -> targets.add(new DeletedTarget(TargetType.OPERATION, item.getId())));
            parameters.forEach(item -> targets.add(new DeletedTarget(TargetType.PARAMETER, item.getId())));
            constraints.forEach(item -> targets.add(new DeletedTarget(TargetType.CONSTRAINT, item.getId())));
            relationships.forEach(item -> targets.add(new DeletedTarget(TargetType.RELATIONSHIP, item.getId())));
            ends.forEach(item -> targets.add(new DeletedTarget(TargetType.RELATIONSHIP_END, item.getId())));
            views.forEach(item -> targets.add(new DeletedTarget(TargetType.DIAGRAM_VIEW, item.getId())));
            notes.forEach(item -> targets.add(new DeletedTarget(TargetType.NOTE, item.getId())));
            return List.copyOf(targets);
        }
    }
}
