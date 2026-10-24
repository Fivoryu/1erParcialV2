package com.diagramador.vcs;

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
import com.diagramador.command.ModelStateHasher;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.ConnectorPath;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.ElementGeometry;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.SeverityKind;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlConstraint;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UmlOperation;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.ConnectorPathRepository;
import com.diagramador.persistence.DiagramViewRepository;
import com.diagramador.persistence.ElementGeometryRepository;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.TypeReferenceRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import com.diagramador.persistence.ViewStyle;
import com.diagramador.persistence.ViewStyleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Captures, encodes, and identity-preservingly restores complete UML model snapshots. */
@Service
public class ModelSnapshotService {

    private final ModelStateHasher stateHasher;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ClassifierRepository classifierRepository;
    private final UmlAttributeRepository attributeRepository;
    private final UmlOperationRepository operationRepository;
    private final OperationParameterRepository parameterRepository;
    private final TypeReferenceRepository typeReferenceRepository;
    private final UmlRelationshipRepository relationshipRepository;
    private final RelationshipEndRepository relationshipEndRepository;
    private final UmlConstraintRepository constraintRepository;
    private final DiagramViewRepository viewRepository;
    private final ElementGeometryRepository geometryRepository;
    private final ConnectorPathRepository connectorPathRepository;
    private final ViewStyleRepository viewStyleRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public ModelSnapshotService(ModelStateHasher stateHasher,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            ClassifierRepository classifierRepository,
            UmlAttributeRepository attributeRepository,
            UmlOperationRepository operationRepository,
            OperationParameterRepository parameterRepository,
            TypeReferenceRepository typeReferenceRepository,
            UmlRelationshipRepository relationshipRepository,
            RelationshipEndRepository relationshipEndRepository,
            UmlConstraintRepository constraintRepository,
            DiagramViewRepository viewRepository,
            ElementGeometryRepository geometryRepository,
            ConnectorPathRepository connectorPathRepository,
            ViewStyleRepository viewStyleRepository) {
        this.stateHasher = stateHasher;
        this.objectMapper = objectMapper;
        this.classifierRepository = classifierRepository;
        this.attributeRepository = attributeRepository;
        this.operationRepository = operationRepository;
        this.parameterRepository = parameterRepository;
        this.typeReferenceRepository = typeReferenceRepository;
        this.relationshipRepository = relationshipRepository;
        this.relationshipEndRepository = relationshipEndRepository;
        this.constraintRepository = constraintRepository;
        this.viewRepository = viewRepository;
        this.geometryRepository = geometryRepository;
        this.connectorPathRepository = connectorPathRepository;
        this.viewStyleRepository = viewStyleRepository;
    }

    @Transactional(readOnly = true)
    public SnapshotModel capture(UmlModel model) {
        UUID modelId = model.getId();
        List<UmlConstraint> constraints = constraintRepository.findByModelId(modelId);
        Map<UUID, List<UmlConstraint>> classifierConstraints = constraints.stream()
                .filter(constraint -> constraint.getOwnerType() == TargetType.CLASSIFIER)
                .collect(java.util.stream.Collectors.groupingBy(UmlConstraint::getOwnerId));
        Map<UUID, List<UmlConstraint>> relationshipConstraints = constraints.stream()
                .filter(constraint -> constraint.getOwnerType() == TargetType.RELATIONSHIP)
                .collect(java.util.stream.Collectors.groupingBy(UmlConstraint::getOwnerId));
        List<UmlConstraint> unsupportedConstraints = constraints.stream()
                .filter(constraint -> constraint.getOwnerType() != TargetType.CLASSIFIER
                        && constraint.getOwnerType() != TargetType.RELATIONSHIP)
                .toList();
        if (!unsupportedConstraints.isEmpty()) {
            throw new IllegalStateException("SnapshotModel cannot represent constraint owner type "
                    + unsupportedConstraints.getFirst().getOwnerType());
        }

        List<SnapshotModel.SnapshotView> views = sortedById(viewRepository.findByModelId(modelId), DiagramView::getId)
                .stream()
                .map(this::snapshotView)
                .toList();
        List<ClassifierResource> classifiers = sortedById(classifierRepository.findByModelId(modelId), Classifier::getId)
                .stream()
                .map(classifier -> classifierResource(classifier,
                        classifierConstraints.getOrDefault(classifier.getId(), List.of())))
                .toList();
        List<RelationshipResource> relationships = sortedById(relationshipRepository.findByModelId(modelId),
                UmlRelationship::getId).stream()
                .map(relationship -> relationshipResource(relationship,
                        relationshipConstraints.getOrDefault(relationship.getId(), List.of())))
                .toList();
        List<SnapshotModel.SnapshotViewStyle> viewStyle = views.stream()
                .flatMap(view -> sortedById(viewStyleRepository.findByViewId(view.id()), ViewStyle::getTargetId).stream()
                        .map(style -> new SnapshotModel.SnapshotViewStyle(view.id(), style.getTargetId(),
                                style.getRouting(), style.getCornerRadius(), style.getStrokeWidth(), style.isDashed(),
                                style.getRouteAdjustment())))
                .toList();
        return new SnapshotModel(views, classifiers, relationships, viewStyle);
    }

    @Transactional(readOnly = true)
    public String stateHash(UmlModel model) {
        return stateHasher.hash(model);
    }

    public JsonNode toJson(SnapshotModel snapshot) {
        return objectMapper.valueToTree(snapshot);
    }

    public SnapshotModel fromJson(JsonNode json) {
        if (json == null || json.isNull()) {
            throw new IllegalArgumentException("Snapshot JSON must be a non-null object");
        }
        try {
            return objectMapper.treeToValue(json, SnapshotModel.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Snapshot JSON does not match the canonical model shape", exception);
        }
    }

    @Transactional
    public void materialize(UmlModel model, SnapshotModel snapshot) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(snapshot, "snapshot");
        SnapshotIndex index = index(snapshot);
        UUID modelId = model.getId();

        Map<UUID, Classifier> existingClassifiers = indexById(classifierRepository.findByModelId(modelId),
                Classifier::getId);
        Map<UUID, UmlRelationship> existingRelationships = indexById(relationshipRepository.findByModelId(modelId),
                UmlRelationship::getId);
        Map<UUID, DiagramView> existingViews = indexById(viewRepository.findByModelId(modelId), DiagramView::getId);
        Map<UUID, TypeReference> existingTypeReferences = indexById(typeReferenceRepository.findByModelId(modelId),
                TypeReference::getId);
        Map<UUID, UmlConstraint> existingConstraints = indexById(constraintRepository.findByModelId(modelId),
                UmlConstraint::getId);

        List<UmlAttribute> currentAttributes = new ArrayList<>();
        List<UmlOperation> currentOperations = new ArrayList<>();
        List<OperationParameter> currentParameters = new ArrayList<>();
        for (Classifier classifier : existingClassifiers.values()) {
            currentAttributes.addAll(attributeRepository.findByClassifierId(classifier.getId()));
            List<UmlOperation> operations = operationRepository.findByClassifierId(classifier.getId());
            currentOperations.addAll(operations);
            for (UmlOperation operation : operations) {
                currentParameters.addAll(parameterRepository.findByOperationId(operation.getId()));
            }
        }
        Map<UUID, UmlAttribute> existingAttributes = indexById(currentAttributes, UmlAttribute::getId);
        Map<UUID, UmlOperation> existingOperations = indexById(currentOperations, UmlOperation::getId);
        Map<UUID, OperationParameter> existingParameters = indexById(currentParameters, OperationParameter::getId);

        List<RelationshipEnd> currentEnds = new ArrayList<>();
        for (UmlRelationship relationship : existingRelationships.values()) {
            currentEnds.addAll(relationshipEndRepository.findByRelationshipId(relationship.getId()));
        }
        Map<UUID, RelationshipEnd> existingEnds = indexById(currentEnds, RelationshipEnd::getId);

        List<ElementGeometry> currentGeometries = new ArrayList<>();
        List<ConnectorPath> currentPaths = new ArrayList<>();
        List<ViewStyle> currentStyles = new ArrayList<>();
        for (DiagramView view : existingViews.values()) {
            currentGeometries.addAll(geometryRepository.findByViewId(view.getId()));
            currentPaths.addAll(connectorPathRepository.findByViewId(view.getId()));
            currentStyles.addAll(viewStyleRepository.findByViewId(view.getId()));
        }
        Map<UUID, ElementGeometry> existingGeometries = indexById(currentGeometries, ElementGeometry::getId);
        Map<UUID, ConnectorPath> existingPaths = indexById(currentPaths, ConnectorPath::getId);
        Map<StyleKey, ViewStyle> existingStyles = new LinkedHashMap<>();
        for (ViewStyle style : currentStyles) {
            existingStyles.put(new StyleKey(style.getView().getId(), style.getTargetId()), style);
        }

        List<UmlAttribute> attributesToReplace = currentAttributes.stream()
                .filter(attribute -> {
                    AttributeSlot target = index.attributes.get(attribute.getId());
                    return target != null && !attribute.getClassifier().getId().equals(target.classifierId());
                })
                .toList();
        Set<UUID> operationsToReplace = new HashSet<>();
        for (OperationSlot slot : index.operations.values()) {
            UmlOperation operation = existingOperations.get(slot.resource().id());
            if (operation != null && !operation.getClassifier().getId().equals(slot.classifierId())) {
                operationsToReplace.add(operation.getId());
            }
        }
        List<UmlOperation> operationsToReplaceEntities = operationsToReplace.stream()
                .map(existingOperations::get)
                .filter(Objects::nonNull)
                .toList();
        List<OperationParameter> parametersToRemove = currentParameters.stream()
                .filter(parameter -> {
                    ParameterSlot target = index.parameters.get(parameter.getId());
                    return target == null
                            || !target.operationId().equals(parameter.getOperation().getId())
                            || operationsToReplace.contains(parameter.getOperation().getId());
                })
                .toList();
        List<RelationshipEnd> endsToReplace = currentEnds.stream()
                .filter(end -> {
                    EndSlot target = index.ends.get(end.getId());
                    return target != null && !end.getRelationship().getId().equals(target.relationshipId());
                })
                .toList();
        List<ElementGeometry> geometriesToReplace = currentGeometries.stream()
                .filter(geometry -> {
                    GeometrySlot target = index.geometries.get(geometry.getId());
                    return target != null && !geometry.getView().getId().equals(target.viewId());
                })
                .toList();
        List<ConnectorPath> pathsToReplace = currentPaths.stream()
                .filter(path -> {
                    PathSlot target = index.paths.get(path.getId());
                    return target != null && (!path.getView().getId().equals(target.viewId())
                            || !path.getRelationship().getId().equals(target.resource().relationshipId()));
                })
                .toList();
        Map<UUID, TypeReferenceTarget> typeReferenceTargets = resolveTypeReferenceTargets(index,
                existingTypeReferences);
        List<TypeReference> typeReferencesToReplace = typeReferenceTargets.entrySet().stream()
                .filter(entry -> {
                    TypeReference current = existingTypeReferences.get(entry.getKey());
                    return current != null && typeReferenceNeedsReplacement(current, entry.getValue());
                })
                .map(entry -> existingTypeReferences.get(entry.getKey()))
                .toList();

        // Pass one clears old namespace edges before classifiers are pruned; identities from the
        // snapshot are inserted or updated with SQL because their UUIDs are caller-assigned.
        entityManager.createNativeQuery("UPDATE diagrammer.classifier SET namespace_id = NULL WHERE model_id = :modelId")
                .setParameter("modelId", modelId)
                .executeUpdate();
        for (ClassifierResource resource : sortedById(index.classifiers.values(), ClassifierResource::id)) {
            validateClassifierAbstractState(resource);
            upsertClassifier(modelId, resource, null);
        }
        classifierRepository.flush();
        flushAndClear();

        deleteAndDetach(typeReferenceRepository, typeReferencesToReplace);
        flushAndClear();
        for (Map.Entry<UUID, TypeReferenceTarget> entry : typeReferenceTargets.entrySet()) {
            upsertTypeReference(entry.getKey(), modelId, entry.getValue());
        }
        flushAndClear();

        deleteAndDetach(attributeRepository, attributesToReplace);
        flushAndClear();
        for (AttributeSlot slot : sortedById(index.attributes.values(), value -> value.resource().id())) {
            upsertAttribute(slot);
        }
        deleteMissing(attributeRepository, existingAttributes, index.attributes.keySet());
        flushAndClear();

        deleteAndDetach(parameterRepository, parametersToRemove);
        parametersToRemove.forEach(parameter -> existingParameters.remove(parameter.getId()));
        flushAndClear();
        deleteAndDetach(operationRepository, operationsToReplaceEntities);
        flushAndClear();
        for (OperationSlot slot : sortedById(index.operations.values(), value -> value.resource().id())) {
            upsertOperation(slot);
        }
        deleteMissing(operationRepository, existingOperations, index.operations.keySet());
        flushAndClear();

        for (ParameterSlot slot : sortedById(index.parameters.values(), value -> value.resource().id())) {
            upsertParameter(slot);
        }
        deleteMissing(parameterRepository, existingParameters, index.parameters.keySet());
        flushAndClear();

        for (RelationshipResource resource : sortedById(index.relationships.values(), RelationshipResource::id)) {
            upsertRelationship(modelId, resource);
        }
        deleteAndDetach(relationshipEndRepository, endsToReplace);
        flushAndClear();
        for (EndSlot slot : sortedById(index.ends.values(), value -> value.resource().id())) {
            upsertRelationshipEnd(slot);
        }
        deleteMissing(relationshipEndRepository, existingEnds, index.ends.keySet());
        flushAndClear();

        for (ConstraintSlot slot : sortedById(index.constraints.values(), value -> value.resource().id())) {
            upsertConstraint(modelId, slot);
        }
        deleteMissing(constraintRepository, existingConstraints, index.constraints.keySet());
        flushAndClear();

        for (SnapshotModel.SnapshotView resource : sortedById(index.views.values(), SnapshotModel.SnapshotView::id)) {
            upsertView(modelId, resource);
        }
        flushAndClear();

        deleteAndDetach(geometryRepository, geometriesToReplace);
        flushAndClear();
        for (GeometrySlot slot : sortedById(index.geometries.values(), value -> value.resource().id())) {
            upsertGeometry(slot);
        }
        deleteMissing(geometryRepository, existingGeometries, index.geometries.keySet());
        flushAndClear();

        deleteAndDetach(connectorPathRepository, pathsToReplace);
        flushAndClear();
        for (PathSlot slot : sortedById(index.paths.values(), value -> value.resource().id())) {
            upsertConnectorPath(slot);
        }
        deleteMissing(connectorPathRepository, existingPaths, index.paths.keySet());
        flushAndClear();

        for (SnapshotModel.SnapshotViewStyle resource : index.styles.values()) {
            upsertViewStyle(resource);
        }
        deleteMissingStyles(existingStyles, index.styles.keySet());
        flushAndClear();

        // Prune in the established FK-safe order after all snapshot-owned rows are restored.
        deleteMissing(relationshipRepository, existingRelationships, index.relationships.keySet());
        flushAndClear();
        deleteMissing(viewRepository, existingViews, index.views.keySet());
        flushAndClear();
        deleteMissing(typeReferenceRepository, existingTypeReferences, index.typeUses.keySet());
        flushAndClear();
        deleteMissing(classifierRepository, existingClassifiers, index.classifiers.keySet());
        flushAndClear();

        // Restore the snapshot hierarchy only after stale classifiers have been pruned.
        for (ClassifierResource resource : sortedById(index.classifiers.values(), ClassifierResource::id)) {
            upsertClassifier(modelId, resource, resource.namespaceId());
        }
        classifierRepository.flush();
        flushAndClear();
    }

    private SnapshotModel.SnapshotView snapshotView(DiagramView view) {
        List<GeometryResource> geometries = sortedById(geometryRepository.findByViewId(view.getId()),
                ElementGeometry::getId).stream()
                .map(geometry -> new GeometryResource(geometry.getId(), geometry.getTargetType(), geometry.getTargetId(),
                        geometry.getX(), geometry.getY(), geometry.getW(), geometry.getH(), geometry.getZOrder()))
                .toList();
        List<ConnectorPathResource> paths = connectorPathRepository.findByViewId(view.getId()).stream()
                .sorted(Comparator.comparingInt(ConnectorPath::getOrdinal).thenComparing(ConnectorPath::getId))
                .map(path -> new ConnectorPathResource(path.getId(), path.getRelationship().getId(), path.getOrdinal(),
                        path.getX(), path.getY()))
                .toList();
        return new SnapshotModel.SnapshotView(view.getId(), view.getName(), view.isDefault(), view.getViewKind(),
                geometries, paths);
    }

    private ClassifierResource classifierResource(Classifier classifier, List<UmlConstraint> constraints) {
        List<ModelResource.AttributeResource> attributes = attributeRepository.findByClassifierId(classifier.getId())
                .stream()
                .sorted(Comparator.comparingInt(UmlAttribute::getOrdinal).thenComparing(UmlAttribute::getId))
                .map(this::attributeResource)
                .toList();
        List<OperationResource> operations = operationRepository.findByClassifierId(classifier.getId()).stream()
                .sorted(Comparator.comparingInt(UmlOperation::getOrdinal).thenComparing(UmlOperation::getId))
                .map(this::operationResource)
                .toList();
        List<ConstraintResource> constraintResources = sortedById(constraints, UmlConstraint::getId).stream()
                .map(constraint -> new ConstraintResource(constraint.getId(), constraint.getExpression(),
                        constraint.getSeverity().name()))
                .toList();
        UUID namespaceId = classifier.getNamespace() == null ? null : classifier.getNamespace().getId();
        return new ClassifierResource(classifier.getId(), classifier.getName(), classifier.getKind(),
                classifier.isAbstract(), namespaceId, attributes, operations, constraintResources);
    }

    private ModelResource.AttributeResource attributeResource(UmlAttribute attribute) {
        TypeReference typeReference = attribute.getTypeReference();
        return new ModelResource.AttributeResource(attribute.getId(), attribute.getName(), attribute.getKind(),
                attribute.getVisibility(), attribute.getMultiplicity(), attribute.getDefaultValue(),
                typeReference == null ? null : typeReference.getId(), typeName(typeReference), attribute.getOrdinal());
    }

    private OperationResource operationResource(UmlOperation operation) {
        TypeReference returnType = operation.getReturnTypeReference();
        List<ParameterResource> parameters = parameterRepository.findByOperationId(operation.getId()).stream()
                .sorted(Comparator.comparingInt(OperationParameter::getOrdinal)
                        .thenComparing(OperationParameter::getId))
                .map(this::parameterResource)
                .toList();
        return new OperationResource(operation.getId(), operation.getName(), operation.getVisibility(),
                operation.isAbstract(), operation.isStatic(), returnType == null ? null : returnType.getId(),
                typeName(returnType), operation.getOrdinal(), parameters);
    }

    private ParameterResource parameterResource(OperationParameter parameter) {
        TypeReference typeReference = parameter.getTypeReference();
        return new ParameterResource(parameter.getId(), parameter.getName(), parameter.getDirection(),
                typeReference == null ? null : typeReference.getId(), typeName(typeReference), parameter.getOrdinal());
    }

    private RelationshipResource relationshipResource(UmlRelationship relationship, List<UmlConstraint> constraints) {
        List<RelationshipEndResource> ends = relationshipEndRepository.findByRelationshipId(relationship.getId())
                .stream()
                .sorted(Comparator.comparingInt(RelationshipEnd::getOrdinal).thenComparing(RelationshipEnd::getId))
                .map(end -> new RelationshipEndResource(end.getId(), end.getClassifier().getId(), end.getRole(),
                        end.getMultiplicity(), end.getNavigability(), end.getAggregationKind(), end.getOrdinal()))
                .toList();
        List<ConstraintResource> constraintResources = sortedById(constraints, UmlConstraint::getId).stream()
                .map(constraint -> new ConstraintResource(constraint.getId(), constraint.getExpression(),
                        constraint.getSeverity().name()))
                .toList();
        return new RelationshipResource(relationship.getId(), relationship.getKind().name(), relationship.getName(),
                ends, constraintResources);
    }

    private String typeName(TypeReference typeReference) {
        if (typeReference == null) {
            return null;
        }
        if (typeReference.getPrimitiveName() != null) {
            return typeReference.getPrimitiveName();
        }
        return typeReference.getClassifier() == null ? null : typeReference.getClassifier().getName();
    }

    private Map<UUID, TypeReferenceTarget> resolveTypeReferenceTargets(SnapshotIndex index,
            Map<UUID, TypeReference> existing) {
        Map<String, List<ClassifierResource>> classifiersByName = new HashMap<>();
        index.classifiers.values().forEach(classifier -> classifiersByName
                .computeIfAbsent(classifier.name(), ignored -> new ArrayList<>()).add(classifier));
        Map<UUID, TypeReferenceTarget> targets = new LinkedHashMap<>();
        for (Map.Entry<UUID, String> entry : index.typeUses.entrySet()) {
            UUID id = entry.getKey();
            String typeName = entry.getValue();
            if (typeName == null) {
                throw new IllegalArgumentException("Type reference " + id + " has no resolved type name");
            }
            TypeReference current = existing.get(id);
            UUID existingClassifierId = current == null || current.getClassifier() == null
                    ? null
                    : current.getClassifier().getId();
            ClassifierResource targetClassifier = resolveTypeClassifier(typeName, classifiersByName,
                    existingClassifierId);
            targets.put(id, new TypeReferenceTarget(targetClassifier == null ? null : targetClassifier.id(),
                    targetClassifier == null ? typeName : null));
        }
        return targets;
    }

    private ClassifierResource resolveTypeClassifier(String typeName,
            Map<String, List<ClassifierResource>> classifiersByName, UUID existingClassifierId) {
        List<ClassifierResource> matching = classifiersByName.getOrDefault(typeName, List.of());
        if (matching.isEmpty()) {
            return null;
        }
        if (matching.size() == 1) {
            return matching.getFirst();
        }
        if (existingClassifierId != null) {
            return matching.stream().filter(classifier -> classifier.id().equals(existingClassifierId)).findFirst()
                    .orElseThrow(() -> ambiguousTypeName(typeName));
        }
        throw ambiguousTypeName(typeName);
    }

    private boolean typeReferenceNeedsReplacement(TypeReference current, TypeReferenceTarget target) {
        boolean currentHasClassifierTarget = current.getClassifier() != null && current.getPrimitiveName() == null;
        boolean currentHasPrimitiveTarget = current.getClassifier() == null && current.getPrimitiveName() != null;
        return target.classifierId() == null ? !currentHasPrimitiveTarget : !currentHasClassifierTarget;
    }

    private IllegalArgumentException ambiguousTypeName(String typeName) {
        return new IllegalArgumentException("Snapshot type name '" + typeName
                + "' matches multiple classifiers and has no classifier id to disambiguate it");
    }

    private void upsertClassifier(UUID modelId, ClassifierResource resource, UUID namespaceId) {
        entityManager.createNativeQuery("""
                INSERT INTO diagrammer.classifier
                    (classifier_id, model_id, namespace_id, kind, name, is_abstract)
                VALUES (:id, :modelId, :namespaceId, CAST(:kind AS diagrammer.classifier_kind), :name, :isAbstract)
                ON CONFLICT (classifier_id) DO UPDATE SET
                    model_id = EXCLUDED.model_id,
                    namespace_id = EXCLUDED.namespace_id,
                    kind = EXCLUDED.kind,
                    name = EXCLUDED.name,
                    is_abstract = EXCLUDED.is_abstract
                """)
                .setParameter("id", resource.id())
                .setParameter("modelId", modelId)
                .setParameter("namespaceId", namespaceId)
                .setParameter("kind", resource.kind().name())
                .setParameter("name", resource.name())
                .setParameter("isAbstract", resource.isAbstract())
                .executeUpdate();
    }

    private void upsertTypeReference(UUID id, UUID modelId, TypeReferenceTarget target) {
        entityManager.createNativeQuery("""
                INSERT INTO diagrammer.type_reference
                    (type_reference_id, model_id, classifier_id, primitive_name)
                VALUES (:id, :modelId, :classifierId, :primitiveName)
                ON CONFLICT (type_reference_id) DO UPDATE SET
                    model_id = EXCLUDED.model_id,
                    classifier_id = EXCLUDED.classifier_id,
                    primitive_name = EXCLUDED.primitive_name
                """)
                .setParameter("id", id)
                .setParameter("modelId", modelId)
                .setParameter("classifierId", target.classifierId())
                .setParameter("primitiveName", target.primitiveName())
                .executeUpdate();
    }

    private void upsertAttribute(AttributeSlot slot) {
        AttributeResource resource = slot.resource();
        entityManager.createNativeQuery("""
                INSERT INTO diagrammer.uml_attribute
                    (attribute_id, classifier_id, type_reference_id, name, kind, visibility,
                     multiplicity, default_value, ordinal)
                VALUES (:id, :classifierId, :typeReferenceId, :name, :kind, :visibility,
                        CAST(:multiplicity AS diagrammer.multiplicity_kind), :defaultValue, :ordinal)
                ON CONFLICT (attribute_id) DO UPDATE SET
                    classifier_id = EXCLUDED.classifier_id,
                    type_reference_id = EXCLUDED.type_reference_id,
                    name = EXCLUDED.name,
                    kind = EXCLUDED.kind,
                    visibility = EXCLUDED.visibility,
                    multiplicity = EXCLUDED.multiplicity,
                    default_value = EXCLUDED.default_value,
                    ordinal = EXCLUDED.ordinal
                """)
                .setParameter("id", resource.id())
                .setParameter("classifierId", slot.classifierId())
                .setParameter("typeReferenceId", resource.typeReferenceId())
                .setParameter("name", resource.name())
                .setParameter("kind", resource.kind())
                .setParameter("visibility", resource.visibility())
                .setParameter("multiplicity", resource.multiplicity() == null ? null : resource.multiplicity().name())
                .setParameter("defaultValue", resource.defaultValue())
                .setParameter("ordinal", resource.ordinal())
                .executeUpdate();
    }

    private void upsertOperation(OperationSlot slot) {
        OperationResource resource = slot.resource();
        entityManager.createNativeQuery("""
                INSERT INTO diagrammer.uml_operation
                    (operation_id, classifier_id, name, return_type_reference_id, visibility,
                     is_abstract, is_static, ordinal)
                VALUES (:id, :classifierId, :name, :returnTypeId, :visibility, :isAbstract, :isStatic, :ordinal)
                ON CONFLICT (operation_id) DO UPDATE SET
                    classifier_id = EXCLUDED.classifier_id,
                    name = EXCLUDED.name,
                    return_type_reference_id = EXCLUDED.return_type_reference_id,
                    visibility = EXCLUDED.visibility,
                    is_abstract = EXCLUDED.is_abstract,
                    is_static = EXCLUDED.is_static,
                    ordinal = EXCLUDED.ordinal
                """)
                .setParameter("id", resource.id())
                .setParameter("classifierId", slot.classifierId())
                .setParameter("name", resource.name())
                .setParameter("returnTypeId", resource.returnTypeReferenceId())
                .setParameter("visibility", resource.visibility())
                .setParameter("isAbstract", resource.isAbstract())
                .setParameter("isStatic", resource.isStatic())
                .setParameter("ordinal", resource.ordinal())
                .executeUpdate();
    }

    private void upsertParameter(ParameterSlot slot) {
        ParameterResource resource = slot.resource();
        entityManager.createNativeQuery("""
                INSERT INTO diagrammer.operation_parameter
                    (parameter_id, operation_id, type_reference_id, name, direction, ordinal)
                VALUES (:id, :operationId, :typeReferenceId, :name, :direction, :ordinal)
                ON CONFLICT (parameter_id) DO UPDATE SET
                    operation_id = EXCLUDED.operation_id,
                    type_reference_id = EXCLUDED.type_reference_id,
                    name = EXCLUDED.name,
                    direction = EXCLUDED.direction,
                    ordinal = EXCLUDED.ordinal
                """)
                .setParameter("id", resource.id())
                .setParameter("operationId", slot.operationId())
                .setParameter("typeReferenceId", resource.typeReferenceId())
                .setParameter("name", resource.name())
                .setParameter("direction", resource.direction())
                .setParameter("ordinal", resource.ordinal())
                .executeUpdate();
    }

    private void upsertRelationship(UUID modelId, RelationshipResource resource) {
        RelationshipKind kind = RelationshipKind.valueOf(resource.kind());
        entityManager.createNativeQuery("""
                INSERT INTO diagrammer.uml_relationship (relationship_id, model_id, kind, name)
                VALUES (:id, :modelId, CAST(:kind AS diagrammer.relationship_kind), :name)
                ON CONFLICT (relationship_id) DO UPDATE SET
                    model_id = EXCLUDED.model_id,
                    kind = EXCLUDED.kind,
                    name = EXCLUDED.name
                """)
                .setParameter("id", resource.id())
                .setParameter("modelId", modelId)
                .setParameter("kind", kind.name())
                .setParameter("name", resource.name())
                .executeUpdate();
    }

    private void upsertRelationshipEnd(EndSlot slot) {
        RelationshipEndResource resource = slot.resource();
        entityManager.createNativeQuery("""
                INSERT INTO diagrammer.relationship_end
                    (relationship_end_id, relationship_id, classifier_id, ordinal, role, multiplicity,
                     navigability, aggregation_kind)
                VALUES (:id, :relationshipId, :classifierId, :ordinal, :role,
                        CAST(:multiplicity AS diagrammer.multiplicity_kind),
                        CAST(:navigability AS diagrammer.navigation_kind), :aggregationKind)
                ON CONFLICT (relationship_end_id) DO UPDATE SET
                    relationship_id = EXCLUDED.relationship_id,
                    classifier_id = EXCLUDED.classifier_id,
                    ordinal = EXCLUDED.ordinal,
                    role = EXCLUDED.role,
                    multiplicity = EXCLUDED.multiplicity,
                    navigability = EXCLUDED.navigability,
                    aggregation_kind = EXCLUDED.aggregation_kind
                """)
                .setParameter("id", resource.id())
                .setParameter("relationshipId", slot.relationshipId())
                .setParameter("classifierId", resource.classifierId())
                .setParameter("ordinal", resource.ordinal())
                .setParameter("role", resource.role())
                .setParameter("multiplicity", resource.multiplicity() == null ? null : resource.multiplicity().name())
                .setParameter("navigability", resource.navigability() == null ? null : resource.navigability().name())
                .setParameter("aggregationKind", resource.aggregationKind())
                .executeUpdate();
    }

    private void upsertConstraint(UUID modelId, ConstraintSlot slot) {
        ConstraintResource resource = slot.resource();
        SeverityKind severity = SeverityKind.valueOf(resource.severity());
        entityManager.createNativeQuery("""
                INSERT INTO diagrammer.uml_constraint
                    (constraint_id, model_id, owner_type, owner_id, expression, severity)
                VALUES (:id, :modelId, CAST(:ownerType AS diagrammer.target_type), :ownerId, :expression,
                        CAST(:severity AS diagrammer.severity_kind))
                ON CONFLICT (constraint_id) DO UPDATE SET
                    model_id = EXCLUDED.model_id,
                    owner_type = EXCLUDED.owner_type,
                    owner_id = EXCLUDED.owner_id,
                    expression = EXCLUDED.expression,
                    severity = EXCLUDED.severity
                """)
                .setParameter("id", resource.id())
                .setParameter("modelId", modelId)
                .setParameter("ownerType", slot.ownerType().name())
                .setParameter("ownerId", slot.ownerId())
                .setParameter("expression", resource.expression())
                .setParameter("severity", severity.name())
                .executeUpdate();
    }

    private void upsertView(UUID modelId, SnapshotModel.SnapshotView resource) {
        entityManager.createNativeQuery("""
                INSERT INTO diagrammer.diagram_view (view_id, model_id, name, view_kind, is_default)
                VALUES (:id, :modelId, :name, :viewKind, :isDefault)
                ON CONFLICT (view_id) DO UPDATE SET
                    model_id = EXCLUDED.model_id,
                    name = EXCLUDED.name,
                    view_kind = EXCLUDED.view_kind,
                    is_default = EXCLUDED.is_default
                """)
                .setParameter("id", resource.id())
                .setParameter("modelId", modelId)
                .setParameter("name", resource.name())
                .setParameter("viewKind", resource.viewKind())
                .setParameter("isDefault", resource.isDefault())
                .executeUpdate();
    }

    private void upsertGeometry(GeometrySlot slot) {
        GeometryResource resource = slot.resource();
        entityManager.createNativeQuery("""
                INSERT INTO diagrammer.element_geometry
                    (geometry_id, view_id, target_type, target_id, x, y, w, h, z_order)
                VALUES (:id, :viewId, CAST(:targetType AS diagrammer.target_type), :targetId,
                        :x, :y, :w, :h, :zOrder)
                ON CONFLICT (geometry_id) DO UPDATE SET
                    view_id = EXCLUDED.view_id,
                    target_type = EXCLUDED.target_type,
                    target_id = EXCLUDED.target_id,
                    x = EXCLUDED.x,
                    y = EXCLUDED.y,
                    w = EXCLUDED.w,
                    h = EXCLUDED.h,
                    z_order = EXCLUDED.z_order
                """)
                .setParameter("id", resource.id())
                .setParameter("viewId", slot.viewId())
                .setParameter("targetType", resource.targetType().name())
                .setParameter("targetId", resource.targetId())
                .setParameter("x", resource.x())
                .setParameter("y", resource.y())
                .setParameter("w", resource.w())
                .setParameter("h", resource.h())
                .setParameter("zOrder", resource.zOrder())
                .executeUpdate();
    }

    private void upsertConnectorPath(PathSlot slot) {
        ConnectorPathResource resource = slot.resource();
        entityManager.createNativeQuery("""
                INSERT INTO diagrammer.connector_path
                    (path_id, view_id, relationship_id, ordinal, x, y)
                VALUES (:id, :viewId, :relationshipId, :ordinal, :x, :y)
                ON CONFLICT (path_id) DO UPDATE SET
                    view_id = EXCLUDED.view_id,
                    relationship_id = EXCLUDED.relationship_id,
                    ordinal = EXCLUDED.ordinal,
                    x = EXCLUDED.x,
                    y = EXCLUDED.y
                """)
                .setParameter("id", resource.id())
                .setParameter("viewId", slot.viewId())
                .setParameter("relationshipId", resource.relationshipId())
                .setParameter("ordinal", resource.ordinal())
                .setParameter("x", resource.x())
                .setParameter("y", resource.y())
                .executeUpdate();
    }

    private void upsertViewStyle(SnapshotModel.SnapshotViewStyle resource) {
        String routeAdjustment = resource.routeAdjustment() == null || resource.routeAdjustment().isNull()
                ? null
                : resource.routeAdjustment().toString();
        entityManager.createNativeQuery("""
                INSERT INTO diagrammer.view_style
                    (view_id, target_type, target_id, routing, corner_radius, stroke_width, dashed, route_adjustment)
                VALUES (:viewId, 'RELATIONSHIP', :targetId, :routing, :cornerRadius, :strokeWidth, :dashed,
                        CAST(:routeAdjustment AS jsonb))
                ON CONFLICT (view_id, target_id) DO UPDATE SET
                    target_type = EXCLUDED.target_type,
                    routing = EXCLUDED.routing,
                    corner_radius = EXCLUDED.corner_radius,
                    stroke_width = EXCLUDED.stroke_width,
                    dashed = EXCLUDED.dashed,
                    route_adjustment = EXCLUDED.route_adjustment,
                    updated_at = now()
                """)
                .setParameter("viewId", resource.viewId())
                .setParameter("targetId", resource.targetId())
                .setParameter("routing", resource.routing())
                .setParameter("cornerRadius", resource.cornerRadius())
                .setParameter("strokeWidth", resource.strokeWidth())
                .setParameter("dashed", resource.dashed())
                .setParameter("routeAdjustment", routeAdjustment)
                .executeUpdate();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private void validateClassifierAbstractState(ClassifierResource resource) {
        if (resource.kind() == null) {
            throw new IllegalArgumentException("Classifier " + resource.id() + " has no kind");
        }
        boolean abstractKind = resource.kind() == ClassifierKind.ABSTRACT_CLASS;
        if (resource.isAbstract() != abstractKind) {
            throw new IllegalArgumentException("Classifier " + resource.id()
                    + " has an abstract flag inconsistent with its kind");
        }
    }

    private SnapshotIndex index(SnapshotModel snapshot) {
        SnapshotIndex index = new SnapshotIndex();
        for (SnapshotModel.SnapshotView view : snapshot.views()) {
            putUnique(index.views, view.id(), view, "view");
            for (GeometryResource geometry : view.elementGeometry()) {
                putUnique(index.geometries, geometry.id(), new GeometrySlot(view.id(), geometry), "geometry");
            }
            for (ConnectorPathResource path : view.connectorPath()) {
                putUnique(index.paths, path.id(), new PathSlot(view.id(), path), "connector path");
            }
        }
        for (ClassifierResource classifier : snapshot.classifiers()) {
            putUnique(index.classifiers, classifier.id(), classifier, "classifier");
            for (AttributeResource attribute : classifier.attributes()) {
                putUnique(index.attributes, attribute.id(), new AttributeSlot(classifier.id(), attribute), "attribute");
            }
            for (OperationResource operation : classifier.operations()) {
                putUnique(index.operations, operation.id(), new OperationSlot(classifier.id(), operation), "operation");
                for (ParameterResource parameter : operation.parameters()) {
                    putUnique(index.parameters, parameter.id(), new ParameterSlot(operation.id(), parameter), "parameter");
                }
            }
            for (ConstraintResource constraint : classifier.constraints()) {
                putUnique(index.constraints, constraint.id(),
                        new ConstraintSlot(TargetType.CLASSIFIER, classifier.id(), constraint), "constraint");
            }
        }
        for (RelationshipResource relationship : snapshot.relationships()) {
            putUnique(index.relationships, relationship.id(), relationship, "relationship");
            for (RelationshipEndResource end : relationship.ends()) {
                putUnique(index.ends, end.id(), new EndSlot(relationship.id(), end), "relationship end");
            }
            for (ConstraintResource constraint : relationship.constraints()) {
                putUnique(index.constraints, constraint.id(),
                        new ConstraintSlot(TargetType.RELATIONSHIP, relationship.id(), constraint), "constraint");
            }
        }
        for (SnapshotModel.SnapshotViewStyle style : snapshot.viewStyle()) {
            StyleKey key = new StyleKey(style.viewId(), style.targetId());
            if (index.styles.putIfAbsent(key, style) != null) {
                throw new IllegalArgumentException("Snapshot contains duplicate view style " + key);
            }
        }
        index.attributes.values().forEach(slot -> addTypeUse(index, slot.resource().typeReferenceId(), slot.resource().type()));
        index.operations.values().forEach(slot -> addTypeUse(index, slot.resource().returnTypeReferenceId(),
                slot.resource().returnType()));
        index.parameters.values().forEach(slot -> addTypeUse(index, slot.resource().typeReferenceId(),
                slot.resource().type()));

        Set<UUID> classifierIds = index.classifiers.keySet();
        Set<UUID> relationshipIds = index.relationships.keySet();
        Set<UUID> viewIds = index.views.keySet();
        Set<UUID> operationIds = index.operations.keySet();
        for (ClassifierResource classifier : index.classifiers.values()) {
            if (classifier.namespaceId() != null && !classifierIds.contains(classifier.namespaceId())) {
                throw new IllegalArgumentException("Classifier " + classifier.id()
                        + " references a namespace absent from the snapshot");
            }
        }
        for (AttributeSlot attribute : index.attributes.values()) {
            validateTypeReference(index, attribute.resource().typeReferenceId());
        }
        for (OperationSlot operation : index.operations.values()) {
            validateTypeReference(index, operation.resource().returnTypeReferenceId());
        }
        for (ParameterSlot parameter : index.parameters.values()) {
            if (!operationIds.contains(parameter.operationId())) {
                throw new IllegalArgumentException("Parameter " + parameter.resource().id()
                        + " references an operation absent from the snapshot");
            }
            validateTypeReference(index, parameter.resource().typeReferenceId());
        }
        for (EndSlot end : index.ends.values()) {
            if (!classifierIds.contains(end.resource().classifierId())) {
                throw new IllegalArgumentException("Relationship end " + end.resource().id()
                        + " references a classifier absent from the snapshot");
            }
        }
        for (GeometrySlot geometry : index.geometries.values()) {
            if (!viewIds.contains(geometry.viewId())) {
                throw new IllegalArgumentException("Geometry references a view absent from the snapshot");
            }
        }
        for (PathSlot path : index.paths.values()) {
            if (!relationshipIds.contains(path.resource().relationshipId())) {
                throw new IllegalArgumentException("Connector path references a relationship absent from the snapshot");
            }
        }
        for (SnapshotModel.SnapshotViewStyle style : index.styles.values()) {
            if (!viewIds.contains(style.viewId())) {
                throw new IllegalArgumentException("View style references a view absent from the snapshot");
            }
        }
        for (ConstraintSlot constraint : index.constraints.values()) {
            boolean ownerExists = constraint.ownerType() == TargetType.CLASSIFIER
                    ? classifierIds.contains(constraint.ownerId())
                    : relationshipIds.contains(constraint.ownerId());
            if (!ownerExists) {
                throw new IllegalArgumentException("Constraint " + constraint.resource().id()
                        + " references an owner absent from the snapshot");
            }
        }
        return index;
    }

    private void addTypeUse(SnapshotIndex index, UUID id, String type) {
        if (id == null) {
            if (type != null) {
                throw new IllegalArgumentException("A type name cannot be stored without a type-reference id");
            }
            return;
        }
        if (index.typeUses.containsKey(id)) {
            if (!Objects.equals(index.typeUses.get(id), type)) {
                throw new IllegalArgumentException("Type reference " + id + " has conflicting resolved names");
            }
        } else {
            index.typeUses.put(id, type);
        }
    }

    private void validateTypeReference(SnapshotIndex index, UUID id) {
        if (id != null && !index.typeUses.containsKey(id)) {
            throw new IllegalArgumentException("Snapshot is missing type-reference data for " + id);
        }
    }

    private <T, ID> void putUnique(Map<ID, T> values, ID id, T value, String kind) {
        if (id == null) {
            throw new IllegalArgumentException("Snapshot " + kind + " is missing its id");
        }
        if (values.putIfAbsent(id, value) != null) {
            throw new IllegalArgumentException("Snapshot contains duplicate " + kind + " id " + id);
        }
    }

    private <T, ID> Map<ID, T> indexById(Collection<T> values, Function<T, ID> id) {
        Map<ID, T> result = new LinkedHashMap<>();
        for (T value : values) {
            result.put(id.apply(value), value);
        }
        return result;
    }

    private <T> List<T> sortedById(Collection<T> values, Function<T, UUID> id) {
        return values.stream().sorted(Comparator.comparing(id)).toList();
    }

    private <T> void deleteAndDetach(JpaRepository<T, UUID> repository, Collection<T> values) {
        if (values.isEmpty()) {
            return;
        }
        repository.deleteAll(values);
        repository.flush();
        values.stream().filter(entityManager::contains).forEach(entityManager::detach);
    }

    private <T> void deleteMissing(JpaRepository<T, UUID> repository, Map<UUID, T> current, Set<UUID> keepIds) {
        List<T> stale = current.entrySet().stream()
                .filter(entry -> !keepIds.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        if (!stale.isEmpty()) {
            repository.deleteAll(stale);
            repository.flush();
        }
    }

    private void deleteMissingStyles(Map<StyleKey, ViewStyle> current, Set<StyleKey> keepKeys) {
        List<ViewStyle> stale = current.entrySet().stream()
                .filter(entry -> !keepKeys.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        if (!stale.isEmpty()) {
            viewStyleRepository.deleteAll(stale);
            viewStyleRepository.flush();
        }
    }

    private record AttributeSlot(UUID classifierId, AttributeResource resource) {
    }

    private record OperationSlot(UUID classifierId, OperationResource resource) {
    }

    private record ParameterSlot(UUID operationId, ParameterResource resource) {
    }

    private record EndSlot(UUID relationshipId, RelationshipEndResource resource) {
    }

    private record ConstraintSlot(TargetType ownerType, UUID ownerId, ConstraintResource resource) {
    }

    private record GeometrySlot(UUID viewId, GeometryResource resource) {
    }

    private record PathSlot(UUID viewId, ConnectorPathResource resource) {
    }

    private record StyleKey(UUID viewId, UUID targetId) {
    }

    private record TypeReferenceTarget(UUID classifierId, String primitiveName) {
    }

    private static final class SnapshotIndex {
        private final Map<UUID, SnapshotModel.SnapshotView> views = new LinkedHashMap<>();
        private final Map<UUID, ClassifierResource> classifiers = new LinkedHashMap<>();
        private final Map<UUID, RelationshipResource> relationships = new LinkedHashMap<>();
        private final Map<UUID, AttributeSlot> attributes = new LinkedHashMap<>();
        private final Map<UUID, OperationSlot> operations = new LinkedHashMap<>();
        private final Map<UUID, ParameterSlot> parameters = new LinkedHashMap<>();
        private final Map<UUID, EndSlot> ends = new LinkedHashMap<>();
        private final Map<UUID, ConstraintSlot> constraints = new LinkedHashMap<>();
        private final Map<UUID, GeometrySlot> geometries = new LinkedHashMap<>();
        private final Map<UUID, PathSlot> paths = new LinkedHashMap<>();
        private final Map<StyleKey, SnapshotModel.SnapshotViewStyle> styles = new LinkedHashMap<>();
        private final Map<UUID, String> typeUses = new LinkedHashMap<>();
    }
}
