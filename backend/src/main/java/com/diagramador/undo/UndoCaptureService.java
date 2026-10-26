package com.diagramador.undo;

import com.diagramador.command.CommandResult;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ModelCommand;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlConstraint;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UmlOperation;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.ModelCommandRepository;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import com.diagramador.persistence.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * IA4-04 capture of undo_log entries: the pre-value snapshot of an envelope's targets taken
 * BEFORE the command runs, stored next to the accepted command. Shared by the manual HTTP path
 * (ProjectApiService.executeCommand) and the AI apply path (AssistantApplyService), so every
 * accepted model command is undoable regardless of its origin.
 */
@Service
public class UndoCaptureService {

    private static final Logger logger = LoggerFactory.getLogger(UndoCaptureService.class);

    private final UmlModelRepository modelRepository;
    private final ModelCommandRepository commandRepository;
    private final UndoLogRepository undoLogRepository;
    private final UserAccountRepository userAccountRepository;
    private final ClassifierRepository classifierRepository;
    private final UmlAttributeRepository attributeRepository;
    private final UmlOperationRepository operationRepository;
    private final OperationParameterRepository parameterRepository;
    private final UmlConstraintRepository constraintRepository;
    private final UmlRelationshipRepository relationshipRepository;
    private final RelationshipEndRepository relationshipEndRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public UndoCaptureService(UmlModelRepository modelRepository, ModelCommandRepository commandRepository,
            UndoLogRepository undoLogRepository, UserAccountRepository userAccountRepository,
            ClassifierRepository classifierRepository, UmlAttributeRepository attributeRepository,
            UmlOperationRepository operationRepository, OperationParameterRepository parameterRepository,
            UmlConstraintRepository constraintRepository, UmlRelationshipRepository relationshipRepository,
            RelationshipEndRepository relationshipEndRepository, ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate) {
        this.modelRepository = modelRepository;
        this.commandRepository = commandRepository;
        this.undoLogRepository = undoLogRepository;
        this.userAccountRepository = userAccountRepository;
        this.classifierRepository = classifierRepository;
        this.attributeRepository = attributeRepository;
        this.operationRepository = operationRepository;
        this.parameterRepository = parameterRepository;
        this.constraintRepository = constraintRepository;
        this.relationshipRepository = relationshipRepository;
        this.relationshipEndRepository = relationshipEndRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Pre-value snapshot of the envelope targets, read inside one transaction so lazy entity
     * graph navigation resolves. Returns null when the capture fails: undo then reports
     * NOT_UNDOABLE for that entry instead of breaking the accepted command.
     */
    public JsonNode snapshotTargets(OperationEnvelope envelope) {
        try {
            return transactionTemplate.execute(status -> doSnapshotTargets(envelope));
        } catch (RuntimeException exception) {
            logger.warn("Failed to snapshot undo targets for operation {}", envelope.operationId(), exception);
            return null;
        }
    }

    /** Stores the undo_log entry for an ACCEPTED command; capture never fails the caller. */
    public void recordAccepted(OperationEnvelope envelope, CommandResult result, UUID userId,
            JsonNode preSnapshot) {
        try {
            UmlModel model = modelRepository.findById(envelope.modelId()).orElse(null);
            ModelCommand command = commandRepository.findById(result.operationId()).orElse(null);
            if (model == null || command == null) {
                // Project-scope commands without a model row (project.create) are not undoable.
                return;
            }
            undoLogRepository.save(UndoLogEntry.create(model,
                    userAccountRepository.findById(userId).orElse(null),
                    command, envelope.type(), preSnapshot));
        } catch (RuntimeException exception) {
            logger.warn("Failed to record undo_log for accepted operation {}", result.operationId(), exception);
        }
    }

    private JsonNode doSnapshotTargets(OperationEnvelope envelope) {
        List<Map<String, Object>> targets = new ArrayList<>();
        for (com.diagramador.command.Target target : envelope.targets()) {
            if (target == null || target.id() == null) {
                continue;
            }
            String scope = target.scope() == null ? "" : target.scope().toLowerCase(Locale.ROOT);
            switch (scope) {
                case "classifier" -> classifierRepository.findById(target.id())
                        .filter(classifier -> classifier.getModel().getId().equals(envelope.modelId()))
                        .ifPresent(classifier -> targets.add(classifierSnapshot(classifier)));
                case "attribute" -> attributeRepository.findById(target.id())
                        .filter(attribute -> attribute.getClassifier().getModel().getId().equals(envelope.modelId()))
                        .ifPresent(attribute -> targets.add(attributeSnapshot(attribute)));
                case "operation" -> operationRepository.findById(target.id())
                        .filter(operation -> operation.getClassifier().getModel().getId().equals(envelope.modelId()))
                        .ifPresent(operation -> targets.add(operationSnapshot(operation)));
                case "relationship" -> relationshipRepository.findById(target.id())
                        .filter(relationship -> relationship.getModel().getId().equals(envelope.modelId()))
                        .ifPresent(relationship -> targets.add(relationshipSnapshot(relationship)));
                case "parameter" -> parameterRepository.findById(target.id())
                        .filter(parameter -> parameter.getOperation().getClassifier().getModel().getId()
                                .equals(envelope.modelId()))
                        .ifPresent(parameter -> targets.add(parameterSnapshot(parameter)));
                case "constraint" -> constraintRepository.findById(target.id())
                        .filter(constraint -> constraint.getModel().getId().equals(envelope.modelId()))
                        .ifPresent(constraint -> targets.add(constraintSnapshot(constraint)));
                default -> {
                    Map<String, Object> unknown = new LinkedHashMap<>();
                    unknown.put("scope", target.scope());
                    unknown.put("id", target.id().toString());
                    targets.add(unknown);
                }
            }
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("targets", targets);
        return objectMapper.valueToTree(snapshot);
    }

    private Map<String, Object> classifierSnapshot(Classifier classifier) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("scope", "classifier");
        snapshot.put("id", classifier.getId().toString());
        snapshot.put("name", classifier.getName());
        snapshot.put("kind", classifier.getKind() == null ? null : classifier.getKind().name());
        snapshot.put("isAbstract", classifier.isAbstract());
        snapshot.put("store", classifier.getStoreOverride() == null ? null : classifier.getStoreOverride().name());
        snapshot.put("namespaceId", classifier.getNamespace() == null ? null
                : classifier.getNamespace().getId().toString());
        return snapshot;
    }

    private Map<String, Object> attributeSnapshot(UmlAttribute attribute) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("scope", "attribute");
        snapshot.put("id", attribute.getId().toString());
        snapshot.put("classifierId", attribute.getClassifier().getId().toString());
        snapshot.put("name", attribute.getName());
        snapshot.put("type", typeSnapshot(attribute.getTypeReference()));
        snapshot.put("visibility", attribute.getVisibility());
        snapshot.put("multiplicity", attribute.getMultiplicity() == null ? null
                : attribute.getMultiplicity().name());
        snapshot.put("defaultValue", attribute.getDefaultValue());
        snapshot.put("ordinal", attribute.getOrdinal());
        return snapshot;
    }

    private Map<String, Object> operationSnapshot(UmlOperation operation) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("scope", "operation");
        snapshot.put("id", operation.getId().toString());
        snapshot.put("classifierId", operation.getClassifier().getId().toString());
        snapshot.put("name", operation.getName());
        snapshot.put("returnType", typeSnapshot(operation.getReturnTypeReference()));
        snapshot.put("visibility", operation.getVisibility());
        snapshot.put("isAbstract", operation.isAbstract());
        snapshot.put("isStatic", operation.isStatic());
        snapshot.put("ordinal", operation.getOrdinal());
        return snapshot;
    }

    private Map<String, Object> relationshipSnapshot(UmlRelationship relationship) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("scope", "relationship");
        snapshot.put("id", relationship.getId().toString());
        snapshot.put("kind", relationship.getKind() == null ? null : relationship.getKind().name());
        snapshot.put("name", relationship.getName());
        snapshot.put("stereotype", relationship.getStereotype());
        snapshot.put("associationClassId",
                relationship.getAssociationClassId() == null ? null : relationship.getAssociationClassId().toString());
        List<Map<String, Object>> ends = new ArrayList<>();
        for (RelationshipEnd end : relationshipEndRepository.findByRelationshipId(relationship.getId())) {
            Map<String, Object> endSnapshot = new LinkedHashMap<>();
            endSnapshot.put("classifierId", end.getClassifier().getId().toString());
            endSnapshot.put("ordinal", (int) end.getOrdinal());
            endSnapshot.put("role", end.getRole());
            endSnapshot.put("multiplicity", end.getMultiplicity() == null ? null : end.getMultiplicity().name());
            endSnapshot.put("navigability", end.getNavigability() == null ? null : end.getNavigability().name());
            ends.add(endSnapshot);
        }
        snapshot.put("ends", ends);
        return snapshot;
    }

    private Map<String, Object> parameterSnapshot(OperationParameter parameter) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("scope", "parameter");
        snapshot.put("id", parameter.getId().toString());
        snapshot.put("operationId", parameter.getOperation().getId().toString());
        snapshot.put("name", parameter.getName());
        snapshot.put("type", typeSnapshot(parameter.getTypeReference()));
        snapshot.put("direction", parameter.getDirection());
        snapshot.put("ordinal", parameter.getOrdinal());
        return snapshot;
    }

    private Map<String, Object> constraintSnapshot(UmlConstraint constraint) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("scope", "constraint");
        snapshot.put("id", constraint.getId().toString());
        snapshot.put("ownerType", constraint.getOwnerType() == null ? null : constraint.getOwnerType().name());
        snapshot.put("ownerId", constraint.getOwnerId() == null ? null : constraint.getOwnerId().toString());
        snapshot.put("expression", constraint.getExpression());
        snapshot.put("severity", constraint.getSeverity() == null ? null : constraint.getSeverity().name());
        return snapshot;
    }

    private Map<String, Object> typeSnapshot(TypeReference reference) {
        if (reference == null) {
            return null;
        }
        if (reference.getPrimitiveName() != null) {
            return Map.of("primitiveName", reference.getPrimitiveName());
        }
        return Map.of("classifierId", reference.getClassifier().getId().toString());
    }
}
