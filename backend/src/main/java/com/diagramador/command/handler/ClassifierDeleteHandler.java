package com.diagramador.command.handler;

import com.diagramador.command.CommandHandler;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ConnectorPath;
import com.diagramador.domain.ElementGeometry;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlConstraint;
import com.diagramador.domain.UmlOperation;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.ConnectorPathRepository;
import com.diagramador.persistence.ElementGeometryRepository;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import com.diagramador.persistence.TypeReferenceRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Deletes a classifier and every model row that cannot survive without it. */
@Component
public class ClassifierDeleteHandler implements CommandHandler {

    private final CommandHandlerSupport support;
    private final ClassifierRepository classifierRepository;
    private final UmlAttributeRepository attributeRepository;
    private final UmlOperationRepository operationRepository;
    private final OperationParameterRepository parameterRepository;
    private final UmlConstraintRepository constraintRepository;
    private final RelationshipEndRepository relationshipEndRepository;
    private final UmlRelationshipRepository relationshipRepository;
    private final TypeReferenceRepository typeReferenceRepository;
    private final ConnectorPathRepository connectorPathRepository;
    private final ElementGeometryRepository geometryRepository;

    public ClassifierDeleteHandler(CommandHandlerSupport support, ClassifierRepository classifierRepository,
            UmlAttributeRepository attributeRepository, UmlOperationRepository operationRepository,
            OperationParameterRepository parameterRepository, UmlConstraintRepository constraintRepository,
            RelationshipEndRepository relationshipEndRepository, UmlRelationshipRepository relationshipRepository,
            TypeReferenceRepository typeReferenceRepository, ConnectorPathRepository connectorPathRepository,
            ElementGeometryRepository geometryRepository) {
        this.support = support;
        this.classifierRepository = classifierRepository;
        this.attributeRepository = attributeRepository;
        this.operationRepository = operationRepository;
        this.parameterRepository = parameterRepository;
        this.constraintRepository = constraintRepository;
        this.relationshipEndRepository = relationshipEndRepository;
        this.relationshipRepository = relationshipRepository;
        this.typeReferenceRepository = typeReferenceRepository;
        this.connectorPathRepository = connectorPathRepository;
        this.geometryRepository = geometryRepository;
    }

    @Override
    public String type() {
        return "classifier.delete";
    }

    @Override
    public Outcome apply(Context context) {
        support.noPayload(context);
        Classifier classifier = support.classifier(context, "classifier");

        List<UmlAttribute> attributes = attributeRepository.findByClassifierId(classifier.getId());
        List<UmlOperation> operations = operationRepository.findByClassifierId(classifier.getId());
        List<TypeReference> typeReferences = typeReferenceRepository.findByClassifierId(classifier.getId());
        List<OperationParameter> parameters = new ArrayList<>();
        for (UmlOperation operation : operations) {
            parameters.addAll(parameterRepository.findByOperationId(operation.getId()));
        }
        List<RelationshipEnd> classifierEnds = relationshipEndRepository.findByClassifierId(classifier.getId());
        Set<UmlRelationship> relationships = new LinkedHashSet<>();
        classifierEnds.forEach(end -> relationships.add(end.getRelationship()));
        List<RelationshipEnd> ends = new ArrayList<>();
        List<ConnectorPath> paths = new ArrayList<>();
        for (UmlRelationship relationship : relationships) {
            ends.addAll(relationshipEndRepository.findByRelationshipId(relationship.getId()));
            paths.addAll(connectorPathRepository.findByRelationshipId(relationship.getId()));
        }
        List<UmlConstraint> constraints = constraintRepository.findByModelId(context.model().getId()).stream()
                .filter(constraint -> (constraint.getOwnerType() == TargetType.CLASSIFIER
                        && classifier.getId().equals(constraint.getOwnerId()))
                        || (constraint.getOwnerType() == TargetType.RELATIONSHIP
                                && relationships.stream().anyMatch(relationship ->
                                        relationship.getId().equals(constraint.getOwnerId()))))
                .toList();

        Set<CommandHandler.DeletedTarget> deletedTargets = new LinkedHashSet<>();
        deletedTargets.add(new CommandHandler.DeletedTarget(TargetType.CLASSIFIER, classifier.getId()));
        attributes.forEach(attribute -> deletedTargets.add(
                new CommandHandler.DeletedTarget(TargetType.ATTRIBUTE, attribute.getId())));
        operations.forEach(operation -> deletedTargets.add(
                new CommandHandler.DeletedTarget(TargetType.OPERATION, operation.getId())));
        parameters.forEach(parameter -> deletedTargets.add(
                new CommandHandler.DeletedTarget(TargetType.PARAMETER, parameter.getId())));
        constraints.forEach(constraint -> deletedTargets.add(
                new CommandHandler.DeletedTarget(TargetType.CONSTRAINT, constraint.getId())));
        relationships.forEach(relationship -> deletedTargets.add(
                new CommandHandler.DeletedTarget(TargetType.RELATIONSHIP, relationship.getId())));
        ends.forEach(end -> deletedTargets.add(
                new CommandHandler.DeletedTarget(TargetType.RELATIONSHIP_END, end.getId())));

        List<ElementGeometry> geometries = geometryRepository.findByViewModelId(context.model().getId()).stream()
                .filter(geometry -> deletedTargets.contains(
                        new CommandHandler.DeletedTarget(geometry.getTargetType(), geometry.getTargetId())))
                .toList();

        // The schema has no target_type member for type_reference, connector_path, or
        // element_geometry. They are physically deleted with this cascade, but cannot be
        // represented by tombstone rows.
        connectorPathRepository.deleteAll(paths);
        relationshipEndRepository.deleteAll(ends);
        relationshipRepository.deleteAll(relationships);
        parameterRepository.deleteAll(parameters);
        operationRepository.deleteAll(operations);
        attributeRepository.deleteAll(attributes);
        constraintRepository.deleteAll(constraints);
        typeReferenceRepository.deleteAll(typeReferences);
        geometryRepository.deleteAll(geometries);
        classifierRepository.delete(classifier);

        return new Outcome(List.copyOf(deletedTargets));
    }
}
