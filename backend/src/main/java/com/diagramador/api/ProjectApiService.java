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
import com.diagramador.command.Author;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ConnectorPath;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.ElementGeometry;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.Project;
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
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectApiService {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SEED_OWNER_SUBJECT = "seed-owner-local";

    private final ProjectRepository projectRepository;
    private final UmlModelRepository modelRepository;
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
    private final com.diagramador.command.CommandService commandService;

    public ProjectApiService(ProjectRepository projectRepository, UmlModelRepository modelRepository,
            ClassifierRepository classifierRepository, UmlAttributeRepository attributeRepository,
            UmlOperationRepository operationRepository, OperationParameterRepository parameterRepository,
            UmlConstraintRepository constraintRepository, UmlRelationshipRepository relationshipRepository,
            RelationshipEndRepository relationshipEndRepository, DiagramViewRepository viewRepository,
            ElementGeometryRepository geometryRepository, ConnectorPathRepository connectorPathRepository,
            com.diagramador.command.CommandService commandService) {
        this.projectRepository = projectRepository;
        this.modelRepository = modelRepository;
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
        this.commandService = commandService;
    }

    public ProjectResource createProject(CreateProjectRequest request) {
        UUID operationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", request.name());
        payload.put("description", request.description());
        OperationEnvelope envelope = new OperationEnvelope(operationId, "project.create",
                new Author(SEED_OWNER_ID, SEED_OWNER_SUBJECT), projectId, modelId, 0L,
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
        return projectRepository.findAll(stableOrder).stream().map(ProjectApiService::projectResource).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResource getProject(UUID projectId) {
        return projectResource(requireProject(projectId));
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
        return commandService.execute(envelope);
    }

    @Transactional(readOnly = true)
    public ModelResource getModel(UUID projectId) {
        requireProject(projectId);
        UmlModel model = modelRepository.findByProjectId(projectId)
                .orElseThrow(() -> notFound("Model was not found."));

        List<UmlConstraint> modelConstraints = constraintRepository.findByModelId(model.getId());
        Map<UUID, List<UmlConstraint>> classifierConstraints = modelConstraints.stream()
                .filter(constraint -> constraint.getOwnerType() == TargetType.CLASSIFIER)
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
                .map(this::relationshipResource)
                .toList();

        return new ModelResource(model.getId(), projectId, model.getCurrentRevision(), views, classifiers,
                relationships);
    }

    private Project requireProject(UUID projectId) {
        return projectRepository.findById(projectId).orElseThrow(() -> notFound("Project was not found."));
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
                classifier.isAbstract(), namespaceId, attributes, operations, constraintResources);
    }

    private AttributeResource attributeResource(UmlAttribute attribute) {
        TypeReference typeReference = attribute.getTypeReference();
        return new AttributeResource(attribute.getId(), attribute.getName(), attribute.getKind(),
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

    private RelationshipResource relationshipResource(UmlRelationship relationship) {
        List<RelationshipEndResource> ends = relationshipEndRepository.findByRelationshipId(relationship.getId())
                .stream()
                .sorted(Comparator.comparingInt(RelationshipEnd::getOrdinal).thenComparing(RelationshipEnd::getId))
                .map(end -> new RelationshipEndResource(end.getId(), end.getClassifier().getId(), end.getRole(),
                        end.getMultiplicity(), end.getNavigability(), end.getAggregationKind(), end.getOrdinal()))
                .toList();
        return new RelationshipResource(relationship.getId(), relationship.getKind().name(), relationship.getName(),
                ends);
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
