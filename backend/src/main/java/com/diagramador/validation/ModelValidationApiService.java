package com.diagramador.validation;

import com.diagramador.api.ApiException;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.Project;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UmlOperation;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.TypeReferenceRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the canonical UML model of a project through the persistence repositories, projects it
 * into the validation read-model and runs the pure semantic rules. Read-only: no validation
 * diagnostic ever mutates the model.
 */
@Service
public class ModelValidationApiService {

    private final ProjectRepository projectRepository;
    private final UmlModelRepository modelRepository;
    private final ClassifierRepository classifierRepository;
    private final UmlRelationshipRepository relationshipRepository;
    private final RelationshipEndRepository relationshipEndRepository;
    private final UmlAttributeRepository attributeRepository;
    private final UmlOperationRepository operationRepository;
    private final OperationParameterRepository parameterRepository;
    private final TypeReferenceRepository typeReferenceRepository;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final ModelValidator modelValidator = new ModelValidator();

    public ModelValidationApiService(ProjectRepository projectRepository,
            UmlModelRepository modelRepository, ClassifierRepository classifierRepository,
            UmlRelationshipRepository relationshipRepository,
            RelationshipEndRepository relationshipEndRepository,
            UmlAttributeRepository attributeRepository, UmlOperationRepository operationRepository,
            OperationParameterRepository parameterRepository,
            TypeReferenceRepository typeReferenceRepository,
            ProjectAuthorizationService projectAuthorizationService) {
        this.projectRepository = projectRepository;
        this.modelRepository = modelRepository;
        this.classifierRepository = classifierRepository;
        this.relationshipRepository = relationshipRepository;
        this.relationshipEndRepository = relationshipEndRepository;
        this.attributeRepository = attributeRepository;
        this.operationRepository = operationRepository;
        this.parameterRepository = parameterRepository;
        this.typeReferenceRepository = typeReferenceRepository;
        this.projectAuthorizationService = projectAuthorizationService;
    }

    @Transactional(readOnly = true)
    public ModelValidationReport validateModel(UUID projectId) {
        requireProject(projectId);
        projectAuthorizationService.requireMember(projectId);
        UmlModel model = modelRepository.findByProjectId(projectId).orElse(null);
        if (model == null) {
            return new ModelValidationReport(List.of());
        }

        List<Classifier> classifiers = classifierRepository.findByModelId(model.getId());
        List<UmlRelationship> relationships = relationshipRepository.findByModelId(model.getId());
        List<RelationshipEnd> relationshipEnds = relationships.stream()
                .flatMap(relationship -> relationshipEndRepository
                        .findByRelationshipId(relationship.getId()).stream())
                .toList();
        List<UmlAttribute> attributes = classifiers.stream()
                .flatMap(classifier -> attributeRepository.findByClassifierId(classifier.getId())
                        .stream())
                .toList();
        List<UmlOperation> operations = classifiers.stream()
                .flatMap(classifier -> operationRepository.findByClassifierId(classifier.getId())
                        .stream())
                .toList();
        List<OperationParameter> parameters = operations.stream()
                .flatMap(operation -> parameterRepository.findByOperationId(operation.getId())
                        .stream())
                .toList();
        List<TypeReference> typeReferences = typeReferenceRepository.findByModelId(model.getId());

        ModelValidationReadModel readModel = ModelValidationReadModel.from(classifiers, relationships,
                relationshipEnds, attributes, operations, parameters, typeReferences);
        return new ModelValidationReport(modelValidator.validate(readModel));
    }

    private Project requireProject(UUID projectId) {
        return projectRepository.findById(projectId).orElseThrow(() -> notFound());
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No se encontró el proyecto.",
                List.of(), null);
    }
}
