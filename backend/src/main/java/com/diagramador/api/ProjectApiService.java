package com.diagramador.api;

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
import com.diagramador.api.ModelResource.RouteAdjustmentResource;
import com.diagramador.api.ModelResource.ViewStyleResource;
import com.diagramador.command.Author;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandResult;
import com.diagramador.command.ErrorCode;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ConnectorPath;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.ElementGeometry;
import com.diagramador.domain.ModelCommand;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.UserAccount;
import com.diagramador.domain.RelationshipEnd;
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
import com.diagramador.persistence.ModelVersionRepository;
import com.diagramador.persistence.ModelCommandRepository;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import com.diagramador.persistence.UserAccountRepository;
import com.diagramador.persistence.ViewStyleRepository;
import com.diagramador.storage.PersistenceStoreClassifier;
import com.diagramador.undo.UndoLogEntry;
import com.diagramador.undo.UndoLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectApiService {

    private final ProjectRepository projectRepository;
    private final ModelCommandRepository modelCommandRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final CurrentUserContext currentUserContext;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final UmlModelRepository modelRepository;
    private final ModelVersionRepository versionRepository;
    private final ClassifierRepository classifierRepository;
    private final UmlAttributeRepository attributeRepository;
    private final UmlOperationRepository operationRepository;
    private final OperationParameterRepository parameterRepository;
    private final UmlConstraintRepository constraintRepository;
    private final UmlRelationshipRepository relationshipRepository;
    private final RelationshipEndRepository relationshipEndRepository;
    private final DiagramViewRepository viewRepository;
    private final ElementGeometryRepository geometryRepository;
    private final ConnectorPathRepository connectorPathRepository;
    private final ViewStyleRepository viewStyleRepository;
    private final com.diagramador.command.CommandService commandService;
    private final com.diagramador.undo.UndoCaptureService undoCaptureService;
    private final NoteResourceAssembler noteResources;

    public ProjectApiService(ProjectRepository projectRepository, ModelCommandRepository modelCommandRepository,
            UmlModelRepository modelRepository,
            ModelVersionRepository versionRepository, ClassifierRepository classifierRepository,
            UmlAttributeRepository attributeRepository,
            UmlOperationRepository operationRepository, OperationParameterRepository parameterRepository,
            UmlConstraintRepository constraintRepository, UmlRelationshipRepository relationshipRepository,
            RelationshipEndRepository relationshipEndRepository, DiagramViewRepository viewRepository,
            ElementGeometryRepository geometryRepository, ConnectorPathRepository connectorPathRepository,
            ViewStyleRepository viewStyleRepository,
            ProjectMemberRepository projectMemberRepository, CurrentUserContext currentUserContext,
            ProjectAuthorizationService projectAuthorizationService,
            com.diagramador.command.CommandService commandService,
            com.diagramador.undo.UndoCaptureService undoCaptureService, NoteResourceAssembler noteResources) {
        this.projectRepository = projectRepository;
        this.noteResources = noteResources;
        this.modelCommandRepository = modelCommandRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.currentUserContext = currentUserContext;
        this.projectAuthorizationService = projectAuthorizationService;
        this.modelRepository = modelRepository;
        this.versionRepository = versionRepository;
        this.classifierRepository = classifierRepository;
        this.attributeRepository = attributeRepository;
        this.operationRepository = operationRepository;
        this.parameterRepository = parameterRepository;
        this.constraintRepository = constraintRepository;
        this.relationshipRepository = relationshipRepository;
        this.relationshipEndRepository = relationshipEndRepository;
        this.viewRepository = viewRepository;
        this.geometryRepository = geometryRepository;
        this.connectorPathRepository = connectorPathRepository;
        this.viewStyleRepository = viewStyleRepository;
        this.commandService = commandService;
        this.undoCaptureService = undoCaptureService;
    }

    public ProjectResource createProject(CreateProjectRequest request) {
        UUID operationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        CurrentUser currentUser = currentUserContext.require();
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", request.name());
        payload.put("description", request.description());
        OperationEnvelope envelope = new OperationEnvelope(operationId, "project.create",
                new Author(currentUser.userId(), currentUser.username()), projectId, modelId, 0L,
                List.of(), List.of(), payload, CommandOrigin.MANUAL, null, operationId.toString());

        CommandResult result = commandService.execute(envelope);
        if (result.status() != CommandStatus.ACCEPTED) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "The project could not be created.", List.of(), operationId);
        }

        Project project = projectRepository.findById(projectId).orElseThrow(() -> internalError(operationId));
        return projectResource(project);
    }

    @Transactional(readOnly = true)
    public List<ProjectResource> listProjects() {
        Sort stableOrder = Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"));
        if (currentUserContext.isOperator()) {
            return projectRepository.findAll(stableOrder).stream()
                    .map(ProjectApiService::projectResource)
                    .toList();
        }

        UUID userId = currentUserContext.require().userId();
        Set<UUID> activeProjectIds = projectMemberRepository.findByUserId(userId).stream()
                .filter(member -> "ACTIVE".equals(member.getStatus()))
                .map(member -> member.getId().getProjectId())
                .collect(Collectors.toSet());
        return projectRepository.findAll(stableOrder).stream()
                .filter(project -> activeProjectIds.contains(project.getId()))
                .map(ProjectApiService::projectResource)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResource getProject(UUID projectId) {
        Project project = requireProject(projectId);
        projectAuthorizationService.requireMember(projectId);
        return projectResource(project);
    }

    public CommandResult executeCommand(UUID pathProjectId, OperationEnvelope envelope) {
        if (envelope == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", "A command envelope is required.",
                    List.of(), null);
        }
        if (!pathProjectId.equals(envelope.projectId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD",
                    "The path project id must match envelope.projectId.", List.of(), envelope.operationId());
        }
        requireProject(pathProjectId);
        CurrentUser currentUser = currentUserContext.require();
        projectAuthorizationService.requireMember(pathProjectId);
        OperationEnvelope authenticatedEnvelope = new OperationEnvelope(envelope.operationId(), envelope.type(),
                new Author(currentUser.userId(), currentUser.username()), envelope.projectId(), envelope.modelId(),
                envelope.baseRevision(), envelope.targets(), envelope.preconditions(), envelope.payload(),
                envelope.origin(), envelope.causalParentOperationId(), envelope.idempotencyKey());
        // IA4-04 capture hook: take the pre-value snapshot before the command runs; fine-grained
        // action authorization remains centralized in CommandService.
        JsonNode preSnapshot = undoCaptureService.snapshotTargets(authenticatedEnvelope);
        CommandResult result = commandService.execute(authenticatedEnvelope);
        for (var diagnostic : result.diagnostics()) {
            if (diagnostic.code() == ErrorCode.FORBIDDEN) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", diagnostic.message(), List.of(),
                        authenticatedEnvelope.operationId());
            }
        }
        if (result.status() == CommandStatus.ACCEPTED) {
            undoCaptureService.recordAccepted(authenticatedEnvelope, result, currentUser.userId(), preSnapshot);
        }
        return result;
    }

    // ---- Accepted-change log (T4/L41): recovery + pending derived-processing count ----

    @Transactional(readOnly = true)
    public List<ModelChangeResource> modelChanges(UUID projectId, Long sinceRevision, Integer limit) {
        requireProject(projectId);
        projectAuthorizationService.requireMember(projectId);
        UUID modelId = modelRepository.findByProjectId(projectId)
                .orElseThrow(() -> notFound("No se encontró el modelo.")).getId();
        int cappedLimit = limit == null ? 50 : Math.max(0, Math.min(limit, 200));
        List<ModelCommand> commands = sinceRevision == null
                ? modelCommandRepository.findByModelIdAndResultRevisionNotNullOrderByResultRevisionDesc(modelId,
                        PageRequest.of(0, cappedLimit))
                : modelCommandRepository.findByModelIdAndResultRevisionNotNullAndResultRevisionGreaterThanOrderByResultRevisionDesc(
                        modelId, sinceRevision, PageRequest.of(0, cappedLimit));
        return commands.stream().map(ProjectApiService::changeResource).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Long> pendingChangeCount(UUID projectId) {
        requireProject(projectId);
        projectAuthorizationService.requireMember(projectId);
        UUID modelId = modelRepository.findByProjectId(projectId)
                .orElseThrow(() -> notFound("No se encontró el modelo.")).getId();
        return Map.of("pendingChanges", modelCommandRepository.countByModelIdAndKnowledgePendingTrue(modelId));
    }

    private static ModelChangeResource changeResource(ModelCommand command) {
        UserAccount author = command.getAuthor();
        return new ModelChangeResource(command.getCommandId(),
                command.getOrigin() == null ? null : command.getOrigin().name(),
                author == null ? null : author.getId(),
                author == null ? null : author.getDisplayName(),
                command.getBaseRevision(), command.getResultRevision(), command.getOperation(),
                command.getTargetType() == null ? null : command.getTargetType().name(),
                command.getTargetId(), command.isKnowledgePending(), command.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public ModelResource getModel(UUID projectId) {
        requireProject(projectId);
        projectAuthorizationService.requireMember(projectId);        UmlModel model = modelRepository.findByProjectId(projectId)
                .orElseThrow(() -> notFound("No se encontró el modelo."));

        List<UmlConstraint> modelConstraints = constraintRepository.findByModelId(model.getId());
        Map<UUID, List<UmlConstraint>> classifierConstraints = modelConstraints.stream()
                .filter(constraint -> constraint.getOwnerType() == TargetType.CLASSIFIER)
                .collect(Collectors.groupingBy(UmlConstraint::getOwnerId));
        Map<UUID, List<UmlConstraint>> relationshipConstraints = modelConstraints.stream()
                .filter(constraint -> constraint.getOwnerType() == TargetType.RELATIONSHIP)
                .collect(Collectors.groupingBy(UmlConstraint::getOwnerId));

        List<ViewResource> views = sortedById(viewRepository.findByModelId(model.getId()), DiagramView::getId).stream()
                .map(this::viewResource)
                .toList();
        List<ClassifierResource> classifiers = sortedById(classifierRepository.findByModelId(model.getId()),
                Classifier::getId).stream()
                .map(classifier -> classifierResource(classifier,
                        classifierConstraints.getOrDefault(classifier.getId(), List.of())))
                .toList();
        List<RelationshipResource> relationships = sortedById(relationshipRepository.findByModelId(model.getId()),
                UmlRelationship::getId).stream()
                .map(relationship -> relationshipResource(relationship,
                        relationshipConstraints.getOrDefault(relationship.getId(), List.of())))
                .toList();

        UUID versionId = versionRepository.findTopByModelIdOrderByRevisionDesc(model.getId())
                .map(version -> version.getId())
                .orElseThrow(() -> notFound("Model version was not found."));
        List<ViewStyleResource> viewStyles = views.stream()
                .flatMap(view -> viewStyleRepository.findByViewId(view.id()).stream()
                        .map(style -> new ViewStyleResource(view.id(), style.getTargetId(), style.getRouting(),
                                style.getCornerRadius(), style.getStrokeWidth(), style.isDashed(),
                                routeAdjustment(style))))
                .toList();
        return withEffectiveStores(new ModelResource(model.getId(), projectId, model.getCurrentRevision(), versionId,
                views, classifiers, relationships, viewStyles, noteResources.forModel(model.getId())));
    }

    /** Fills each class with the store the generator will use and why, so every consumer reads one decision. */
    private static ModelResource withEffectiveStores(ModelResource model) {
        Map<UUID, PersistenceStoreClassifier.Decision> decisions = PersistenceStoreClassifier.classify(model);
        List<ClassifierResource> classifiers = model.classifiers().stream()
                .map(classifier -> decisions.containsKey(classifier.id())
                        ? classifier.withStore(decisions.get(classifier.id()).store(),
                                decisions.get(classifier.id()).reason())
                        : classifier)
                .toList();
        return new ModelResource(model.id(), model.projectId(), model.revision(), model.versionId(), model.views(),
                classifiers, model.relationships(), model.viewStyle(), model.notes());
    }

    /** ROR-02: null-safe read of the persisted frame-relative orthogonal adjustment. */
    private RouteAdjustmentResource routeAdjustment(com.diagramador.persistence.ViewStyle style) {
        JsonNode adjustment = style.getRouteAdjustment();
        if (adjustment == null || adjustment.isNull() || !adjustment.isObject()) {
            return null;
        }
        return new RouteAdjustmentResource(
                adjustment.path("axis").asText(null),
                integersOf(adjustment.path("corridors")),
                integersOf(adjustment.path("levels")),
                anchorsOf(adjustment.path("anchors")));
    }

    /** ANC-01: null-safe read of the persisted per-end route anchors. */
    private ModelResource.RouteAnchorsResource anchorsOf(JsonNode anchors) {
        if (anchors == null || anchors.isNull() || !anchors.isObject()) {
            return null;
        }
        return new ModelResource.RouteAnchorsResource(
                anchorOf(anchors.path("source")),
                anchorOf(anchors.path("target")));
    }

    private ModelResource.RouteAnchorResource anchorOf(JsonNode anchor) {
        if (anchor == null || anchor.isNull() || !anchor.isObject()) {
            return null;
        }
        return new ModelResource.RouteAnchorResource(
                anchor.path("side").asText(null),
                anchor.path("offset").asInt(0));
    }

    private List<Integer> integersOf(JsonNode values) {
        List<Integer> integers = new java.util.ArrayList<>();
        if (values.isArray()) {
            values.forEach(value -> integers.add(value.asInt()));
        }
        return integers;
    }

    private Project requireProject(UUID projectId) {
        return projectRepository.findById(projectId).orElseThrow(() -> notFound("No se encontró el proyecto."));
    }

    private ViewResource viewResource(DiagramView view) {
        List<GeometryResource> geometry = sortedById(geometryRepository.findByViewId(view.getId()),
                ElementGeometry::getId).stream()
                .map(element -> new GeometryResource(element.getId(), element.getTargetType(), element.getTargetId(),
                        element.getX(), element.getY(), element.getW(), element.getH(), element.getZOrder()))
                .toList();
        List<ConnectorPathResource> paths = connectorPathRepository.findByViewId(view.getId()).stream()
                .sorted(Comparator.comparingInt(ConnectorPath::getOrdinal).thenComparing(ConnectorPath::getId))
                .map(path -> new ConnectorPathResource(path.getId(), path.getRelationship().getId(), path.getOrdinal(),
                        path.getX(), path.getY()))
                .toList();
        return new ViewResource(view.getId(), view.getName(), view.isDefault(), geometry, paths);
    }

    private ClassifierResource classifierResource(Classifier classifier, List<UmlConstraint> constraints) {
        List<AttributeResource> attributes = attributeRepository.findByClassifierId(classifier.getId()).stream()
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
                classifier.isAbstract(), namespaceId, attributes, operations, constraintResources,
                classifier.getStoreOverride(), null, null, classifier.getStereotype());
    }

    private AttributeResource attributeResource(UmlAttribute attribute) {
        TypeReference typeReference = attribute.getTypeReference();
        return new AttributeResource(attribute.getId(), attribute.getName(), attribute.getKind(),
                attribute.getVisibility(), attribute.getMultiplicity(), attribute.getDefaultValue(),
                typeReference == null ? null : typeReference.getId(), typeName(typeReference), attribute.getOrdinal(),
                attribute.getLowerBound(), attribute.getUpperBound(), attribute.getStereotype());
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
                typeName(returnType), operation.getOrdinal(), parameters, operation.getStereotype());
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
                        end.getMultiplicity(), end.getNavigability(), end.getAggregationKind(), end.getOrdinal(),
                        end.getLowerBound(), end.getUpperBound()))
                .toList();
        List<ConstraintResource> constraintResources = sortedById(constraints, UmlConstraint::getId).stream()
                .map(constraint -> new ConstraintResource(constraint.getId(), constraint.getExpression(),
                        constraint.getSeverity().name()))
                .toList();
        return new RelationshipResource(relationship.getId(), relationship.getKind().name(), relationship.getName(),
                relationship.getStereotype(), relationship.getAssociationClassId(), ends, constraintResources);
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

    private static ProjectResource projectResource(Project project) {
        return new ProjectResource(project.getId(), project.getName(), project.getDescription());
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, List.of(), null);
    }

    private static ApiException internalError(UUID operationId) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected server error occurred.", List.of(), operationId);
    }

    private static <T> List<T> sortedById(List<T> values, java.util.function.Function<T, UUID> id) {
        List<T> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.comparing(id));
        return sorted;
    }
}
