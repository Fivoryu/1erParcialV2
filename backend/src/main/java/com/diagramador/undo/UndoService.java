package com.diagramador.undo;

import com.diagramador.command.Author;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.command.Precondition;
import com.diagramador.command.Target;
import com.diagramador.domain.ModelCommand;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UmlOperation;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * IA4-04: collaborative undo/redo by compensation. Undo applies a NEW inverse command
 * (origin UNDO) through the normal CommandService pipeline; redo re-applies the ORIGINAL
 * payload as a NEW command (origin REDO). Never a snapshot: interleaved foreign commands
 * survive because the inverse is applied forward over the current state.
 */
@Service
public class UndoService {

    private final UmlModelRepository modelRepository;
    private final UndoLogRepository undoLogRepository;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final CurrentUserContext currentUserContext;
    private final CommandService commandService;
    private final UmlAttributeRepository attributeRepository;
    private final UmlOperationRepository operationRepository;
    private final OperationParameterRepository parameterRepository;
    private final UmlConstraintRepository constraintRepository;
    private final RelationshipEndRepository relationshipEndRepository;
    private final ObjectMapper objectMapper;
    private final ClassifierRepository classifierRepository;
    private final UmlRelationshipRepository relationshipRepository;

    public UndoService(UmlModelRepository modelRepository, UndoLogRepository undoLogRepository,
            ProjectAuthorizationService projectAuthorizationService, CurrentUserContext currentUserContext,
            CommandService commandService, UmlAttributeRepository attributeRepository,
            UmlOperationRepository operationRepository, OperationParameterRepository parameterRepository,
            UmlConstraintRepository constraintRepository, RelationshipEndRepository relationshipEndRepository,
            ObjectMapper objectMapper, ClassifierRepository classifierRepository,
            UmlRelationshipRepository relationshipRepository) {
        this.classifierRepository = classifierRepository;
        this.relationshipRepository = relationshipRepository;
        this.modelRepository = modelRepository;
        this.undoLogRepository = undoLogRepository;
        this.projectAuthorizationService = projectAuthorizationService;
        this.currentUserContext = currentUserContext;
        this.commandService = commandService;
        this.attributeRepository = attributeRepository;
        this.operationRepository = operationRepository;
        this.parameterRepository = parameterRepository;
        this.constraintRepository = constraintRepository;
        this.relationshipEndRepository = relationshipEndRepository;
        this.objectMapper = objectMapper;
    }

    public UndoResult undo(UUID projectId) {
        UmlModel model = requireModel(projectId);
        projectAuthorizationService.requireMember(projectId);
        CurrentUser user = currentUserContext.require();
        UndoLogEntry entry = undoLogRepository
                .findFirstByModel_IdAndUser_IdAndUndoneAtIsNullOrderByCreatedAtDescIdDesc(model.getId(), user.userId())
                .orElse(null);
        if (entry == null) {
            return UndoResult.rejected("NOTHING_TO_UNDO", "No hay acciones para deshacer.");
        }
        if ("xmi.import".equals(entry.getType())) {
            return UndoResult.rejected("XMI_IMPORT_NOT_UNDOABLE", "La importación de XMI no es deshacible.");
        }
        Inverse inverse = inverseOf(entry, model.getId(), projectId);
        if (inverse.rejection() != null) {
            return inverse.rejection();
        }
        CommandResult result = commandService.execute(inverse.envelope());
        if (result.status() != CommandStatus.ACCEPTED) {
            return UndoResult.ofRejection(result);
        }
        entry.markUndone(OffsetDateTime.now());
        undoLogRepository.save(entry);
        return UndoResult.accepted(result);
    }

    public UndoResult redo(UUID projectId) {
        UmlModel model = requireModel(projectId);
        projectAuthorizationService.requireMember(projectId);
        CurrentUser user = currentUserContext.require();
        UndoLogEntry entry = undoLogRepository
                .findFirstByModel_IdAndUser_IdAndUndoneAtIsNotNullAndRedoneAtIsNullOrderByUndoneAtDescIdDesc(
                        model.getId(), user.userId())
                .orElse(null);
        if (entry == null) {
            return UndoResult.rejected("NOTHING_TO_REDO", "No hay acciones para rehacer.");
        }
        if ("xmi.import".equals(entry.getType())) {
            return UndoResult.rejected("XMI_IMPORT_NOT_UNDOABLE", "La importación de XMI no es rehacible.");
        }
        ModelCommand original = entry.getCommand();
        JsonNode canonical = original.getCanonicalEnvelope();
        long baseRevision = freshRevision(model.getId());

        // Replay with the same idempotency key would return the old result without mutating.
        OperationEnvelope envelope = new OperationEnvelope(UUID.randomUUID(), entry.getType(),
                new Author(user.userId(), user.username()), projectId, model.getId(), baseRevision,
                parseTargets(canonical.path("targets")), parsePreconditions(canonical.path("preconditions")),
                objectMapper.convertValue(canonical.path("payload"), Object.class), CommandOrigin.REDO,
                original.getCommandId(), UUID.randomUUID().toString());
        CommandResult result = commandService.execute(envelope);
        if (result.status() != CommandStatus.ACCEPTED) {
            return UndoResult.ofRejection(result);
        }
        entry.markRedone(OffsetDateTime.now());
        undoLogRepository.save(entry);
        return UndoResult.accepted(result);
    }

    // --- inverse construction -----------------------------------------------------------

    private Inverse inverseOf(UndoLogEntry entry, UUID modelId, UUID projectId) {
        JsonNode canonical = entry.getCommand().getCanonicalEnvelope();
        JsonNode payload = canonical.path("payload");
        long baseRevision = freshRevision(modelId);
        List<Target> originalTargets = parseTargets(canonical.path("targets"));
        return switch (entry.getType()) {
            case "classifier.create" -> inverseOfCreate(entry, payload, modelId, projectId, baseRevision);
            case "classifier.rename" -> inverseOfSnapshotUpdate(entry, "classifier.rename", "name",
                    originalTargets, baseRevision, modelId, projectId);
            case "classifier.setAbstract" -> inverseOfSnapshotUpdate(entry, "classifier.setAbstract", "isAbstract",
                    originalTargets, baseRevision, modelId, projectId);
            case "classifier.setStore" -> inverseOfSnapshotUpdate(entry, "classifier.setStore", "store",
                    originalTargets, baseRevision, modelId, projectId);
            case "classifier.stereotype.set" -> inverseOfSnapshotUpdate(entry, "classifier.stereotype.set",
                    "stereotype", originalTargets, baseRevision, modelId, projectId);
            case "attribute.add" -> Inverse.of(new OperationEnvelope(UUID.randomUUID(), "attribute.remove",
                    author(), projectId, modelId, baseRevision,
                    List.of(new Target("attribute", uuid(payload.path("id")))), List.of(), Map.of(),
                    CommandOrigin.UNDO, entry.getCommand().getCommandId(), UUID.randomUUID().toString()));
            case "attribute.remove" -> inverseOfAttributeRemove(entry, modelId, projectId, baseRevision);
            case "attribute.update" -> inverseOfSnapshotUpdate(entry, "attribute.update", null,
                    originalTargets, baseRevision, modelId, projectId);
            case "relationship.create" -> inverseOfRelationshipCreate(entry, payload, modelId, projectId,
                    baseRevision);
            case "relationship.delete" -> inverseOfRelationshipDelete(entry, modelId, projectId, baseRevision);
            case "note.create" -> inverseOfNoteCreate(entry, payload, modelId, projectId, baseRevision);
            case "note.update" -> inverseOfSnapshotUpdate(entry, "note.update", "body", originalTargets,
                    baseRevision, modelId, projectId);
            case "note.delete" -> inverseOfNoteDelete(entry, modelId, projectId, baseRevision);
            case "note.link" -> inverseOfNoteLink(entry, payload, "note.unlink", originalTargets, modelId, projectId,
                    baseRevision);
            case "note.unlink" -> inverseOfNoteLink(entry, payload, "note.link", originalTargets, modelId, projectId,
                    baseRevision);
            default -> Inverse.rejected("NOT_UNDOABLE",
                    "La operación " + entry.getType() + " no tiene inverso declarado.");
        };
    }

    private Inverse inverseOfCreate(UndoLogEntry entry, JsonNode payload, UUID modelId, UUID projectId,
            long baseRevision) {
        UUID classifierId = uuid(payload.path("id"));
        int cascade = classifierCascadeCount(modelId, classifierId);
        if (cascade > 1) {
            // Declared rejection, no work is done: the confirm flow belongs to the caller.
            return Inverse.rejected("CASCADE_REQUIRED", "Deshacer crear requiere confirmar el borrado en cascada de "
                    + cascade + " elementos.");
        }
        return Inverse.of(new OperationEnvelope(UUID.randomUUID(), "classifier.delete", author(), projectId,
                modelId, baseRevision, List.of(new Target("classifier", classifierId)), List.of(), Map.of(),
                CommandOrigin.UNDO, entry.getCommand().getCommandId(), UUID.randomUUID().toString()));
    }

    private Inverse inverseOfSnapshotUpdate(UndoLogEntry entry, String inverseType, String valueField,
            List<Target> originalTargets, long baseRevision, UUID modelId, UUID projectId) {
        JsonNode snapshot = firstTargetSnapshot(entry);
        if (snapshot.isMissingNode()) {
            return Inverse.rejected("NOT_UNDOABLE", "El valor previo no fue capturado para " + entry.getType() + ".");
        }
        Map<String, Object> payload;
        if (valueField == null) {
            // update: restore every previous field the snapshot captured.
            payload = new java.util.LinkedHashMap<>();
            for (String field : List.of("name", "type", "visibility", "multiplicity", "defaultValue")) {
                JsonNode value = snapshot.path(field);
                if (!value.isMissingNode() && !value.isNull()) {
                    payload.put(field, objectMapper.convertValue(value, Object.class));
                }
            }
            restoreRangeBounds(snapshot, payload);
            // Only when the update touched it: the previous stereotype comes back, "none" as an explicit null.
            if (entry.getCommand().getCanonicalEnvelope().path("payload").has("stereotype")) {
                payload.put("stereotype", snapshot.path("stereotype").isTextual()
                        ? snapshot.path("stereotype").asText() : null);
            }
            // Only when the update touched them: the previous flag value comes back as an explicit boolean.
            JsonNode updated = entry.getCommand().getCanonicalEnvelope().path("payload");
            for (String flag : List.of("isStatic", "isDerived")) {
                if (updated.has(flag)) {
                    payload.put(flag, snapshot.path(flag).asBoolean(false));
                }
            }
            if (payload.isEmpty()) {
                return Inverse.rejected("NOT_UNDOABLE", "El valor previo no fue capturado para " + entry.getType()
                        + ".");
            }
        } else {
            // A HashMap, not Map.of: restoring "no override" legitimately carries a null value.
            payload = new java.util.HashMap<>();
            payload.put(valueField, objectMapper.convertValue(snapshot.path(valueField), Object.class));
        }
        return Inverse.of(new OperationEnvelope(UUID.randomUUID(), inverseType, author(), projectId,
                modelId, baseRevision, originalTargets, List.of(), payload, CommandOrigin.UNDO,
                entry.getCommand().getCommandId(), UUID.randomUUID().toString()));
    }

    private Inverse inverseOfAttributeRemove(UndoLogEntry entry, UUID modelId, UUID projectId, long baseRevision) {
        JsonNode snapshot = firstTargetSnapshot(entry);
        if (snapshot.isMissingNode() || snapshot.path("classifierId").isMissingNode()) {
            return Inverse.rejected("NOT_UNDOABLE", "El valor previo no fue capturado para attribute.remove.");
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("id", snapshot.path("id").asText());
        for (String field : List.of("name", "type", "visibility", "multiplicity", "defaultValue", "ordinal",
                "stereotype")) {
            JsonNode value = snapshot.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                payload.put(field, objectMapper.convertValue(value, Object.class));
            }
        }
        restoreRangeBounds(snapshot, payload);
        for (String flag : List.of("isStatic", "isDerived")) {
            if (snapshot.path(flag).asBoolean(false)) {
                payload.put(flag, true);
            }
        }
        UUID classifierId = uuid(snapshot.path("classifierId"));
        return Inverse.of(new OperationEnvelope(UUID.randomUUID(), "attribute.add", author(), projectId,
                modelId, baseRevision, List.of(new Target("classifier", classifierId)), List.of(), payload,
                CommandOrigin.UNDO, entry.getCommand().getCommandId(), UUID.randomUUID().toString()));
    }

    private Inverse inverseOfNoteCreate(UndoLogEntry entry, JsonNode payload, UUID modelId, UUID projectId,
            long baseRevision) {
        // A note created without a client id has no recorded id to delete again.
        if (!payload.hasNonNull("id")) {
            return Inverse.rejected("NOT_UNDOABLE", "note.create sin id no tiene inverso declarado.");
        }
        return Inverse.of(new OperationEnvelope(UUID.randomUUID(), "note.delete", author(), projectId, modelId,
                baseRevision, List.of(new Target("note", uuid(payload.path("id")))), List.of(), Map.of(),
                CommandOrigin.UNDO, entry.getCommand().getCommandId(), UUID.randomUUID().toString()));
    }

    private Inverse inverseOfNoteDelete(UndoLogEntry entry, UUID modelId, UUID projectId, long baseRevision) {
        JsonNode snapshot = firstTargetSnapshot(entry);
        if (snapshot.isMissingNode() || snapshot.path("body").isMissingNode()) {
            return Inverse.rejected("NOT_UNDOABLE", "El valor previo no fue capturado para note.delete.");
        }
        // Only the elements still in the model come back: another editor may have deleted one since.
        List<String> annotated = new ArrayList<>();
        for (JsonNode link : snapshot.path("annotatedElements")) {
            UUID targetId = uuid(link.path("targetId"));
            boolean present = "RELATIONSHIP".equals(link.path("targetType").asText())
                    ? relationshipRepository.existsById(targetId) : classifierRepository.existsById(targetId);
            if (present) {
                annotated.add(targetId.toString());
            }
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("id", snapshot.path("id").asText());
        payload.put("body", snapshot.path("body").asText());
        payload.put("annotatedElementIds", annotated);
        return Inverse.of(new OperationEnvelope(UUID.randomUUID(), "note.create", author(), projectId, modelId,
                baseRevision, List.of(), List.of(), payload, CommandOrigin.UNDO, entry.getCommand().getCommandId(),
                UUID.randomUUID().toString()));
    }

    private Inverse inverseOfNoteLink(UndoLogEntry entry, JsonNode payload, String inverseType,
            List<Target> originalTargets, UUID modelId, UUID projectId, long baseRevision) {
        Map<String, Object> inversePayload = new java.util.LinkedHashMap<>();
        inversePayload.put("targetType", payload.path("targetType").asText());
        inversePayload.put("targetId", payload.path("targetId").asText());
        return Inverse.of(new OperationEnvelope(UUID.randomUUID(), inverseType, author(), projectId, modelId,
                baseRevision, originalTargets, List.of(), inversePayload, CommandOrigin.UNDO,
                entry.getCommand().getCommandId(), UUID.randomUUID().toString()));
    }

    private Inverse inverseOfRelationshipCreate(UndoLogEntry entry, JsonNode payload, UUID modelId, UUID projectId,
            long baseRevision) {
        UUID relationshipId = uuid(payload.path("id"));
        List<Precondition> preconditions = List.of(new Precondition("cascade_count",
                relationshipCascadeCount(modelId, relationshipId), null));
        return Inverse.of(new OperationEnvelope(UUID.randomUUID(), "relationship.delete", author(), projectId,
                modelId, baseRevision, List.of(new Target("relationship", relationshipId)), preconditions,
                Map.of(), CommandOrigin.UNDO, entry.getCommand().getCommandId(), UUID.randomUUID().toString()));
    }

    private Inverse inverseOfRelationshipDelete(UndoLogEntry entry, UUID modelId, UUID projectId, long baseRevision) {
        JsonNode snapshot = firstTargetSnapshot(entry);
        if (snapshot.isMissingNode() || snapshot.path("ends").size() < 2) {
            return Inverse.rejected("NOT_UNDOABLE", "El valor previo no fue capturado para relationship.delete.");
        }
        JsonNode source = endByOrdinal(snapshot, 1);
        JsonNode target = endByOrdinal(snapshot, 2);
        if (source == null || target == null) {
            return Inverse.rejected("NOT_UNDOABLE", "El valor previo no fue capturado para relationship.delete.");
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("id", snapshot.path("id").asText());
        payload.put("kind", snapshot.path("kind").asText());
        if (!snapshot.path("name").isNull() && !snapshot.path("name").isMissingNode()) {
            payload.put("name", snapshot.path("name").asText());
        }
        if (!snapshot.path("stereotype").isNull() && !snapshot.path("stereotype").isMissingNode()) {
            payload.put("stereotype", snapshot.path("stereotype").asText());
        }
        // The link comes back only if its class is still there: another editor may have deleted it since, and
        // restoring the relationship matters more than the link.
        if (!snapshot.path("associationClassId").isNull() && !snapshot.path("associationClassId").isMissingNode()
                && classifierRepository.existsById(uuid(snapshot.path("associationClassId")))) {
            payload.put("associationClassId", snapshot.path("associationClassId").asText());
        }
        payload.put("source", restoredEnd(source));
        payload.put("target", restoredEnd(target));
        return Inverse.of(new OperationEnvelope(UUID.randomUUID(), "relationship.create", author(), projectId,
                modelId, baseRevision,
                List.of(new Target("classifier", uuid(source.path("classifierId"))),
                        new Target("classifier", uuid(target.path("classifierId")))),
                List.of(), payload, CommandOrigin.UNDO, entry.getCommand().getCommandId(),
                UUID.randomUUID().toString()));
    }

    /** The end payload of a recreated relationship: its classifier, plus each ordered/unique flag that was set. */
    private static Map<String, Object> restoredEnd(JsonNode end) {
        Map<String, Object> restored = new java.util.LinkedHashMap<>();
        restored.put("classifierId", end.path("classifierId").asText());
        for (String flag : List.of("isOrdered", "isUnique")) {
            if (end.path(flag).asBoolean(false)) {
                restored.put(flag, true);
            }
        }
        return restored;
    }

    /**
     * A previous RANGE restores both bounds, an unbounded upper bound included as an explicit null; any other
     * previous multiplicity restores none, because restoring the kind already clears the bounds.
     */
    private static void restoreRangeBounds(JsonNode snapshot, Map<String, Object> payload) {
        if (!"RANGE".equals(snapshot.path("multiplicity").asText(null))) {
            return;
        }
        payload.put("lowerBound", snapshot.path("lowerBound").isNumber() ? snapshot.path("lowerBound").asInt() : null);
        payload.put("upperBound", snapshot.path("upperBound").isNumber() ? snapshot.path("upperBound").asInt() : null);
    }

    // --- capture-shape helpers -----------------------------------------------------------

    private JsonNode firstTargetSnapshot(UndoLogEntry entry) {
        JsonNode targets = entry.getPreSnapshot() == null ? null : entry.getPreSnapshot().path("targets");
        return targets == null || targets.isEmpty() ? com.fasterxml.jackson.databind.node.MissingNode.getInstance()
                : targets.get(0);
    }

    private static JsonNode endByOrdinal(JsonNode relationshipSnapshot, int ordinal) {
        for (JsonNode end : relationshipSnapshot.path("ends")) {
            if (end.path("ordinal").asInt(-1) == ordinal) {
                return end;
            }
        }
        return null;
    }

    private List<Target> parseTargets(JsonNode targets) {
        List<Target> parsed = new ArrayList<>();
        for (JsonNode target : targets) {
            parsed.add(new Target(target.path("scope").asText(null), uuid(target.path("id"))));
        }
        return parsed;
    }

    private List<Precondition> parsePreconditions(JsonNode preconditions) {
        List<Precondition> parsed = new ArrayList<>();
        for (JsonNode precondition : preconditions) {
            parsed.add(new Precondition(precondition.path("kind").asText(null),
                    plainValue(precondition.path("expect")), nullOrText(precondition.path("scope"))));
        }
        return parsed;
    }

    private static Object plainValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node;
    }

    private static String nullOrText(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private static UUID uuid(JsonNode node) {
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("The captured snapshot does not carry a parsable id", exception);
        }
    }

    private Author author() {
        CurrentUser user = currentUserContext.require();
        return new Author(user.userId(), user.username());
    }

    // --- cascade counting (mirrors ClassifierDeleteHandler / RelationshipDeleteHandler) --

    private int classifierCascadeCount(UUID modelId, UUID classifierId) {
        List<UmlOperation> operations = operationRepository.findByClassifierId(classifierId);
        int parameters = operations.stream()
                .mapToInt(operation -> parameterRepository.findByOperationId(operation.getId()).size()).sum();
        Set<UUID> relationshipIds = new LinkedHashSet<>();
        relationshipEndRepository.findByClassifierId(classifierId)
                .forEach(end -> relationshipIds.add(end.getRelationship().getId()));
        int ends = relationshipIds.stream()
                .mapToInt(relationshipId -> relationshipEndRepository.findByRelationshipId(relationshipId).size())
                .sum();
        int constraints = (int) constraintRepository.findByModelId(modelId).stream()
                .filter(constraint -> (constraint.getOwnerType() == TargetType.CLASSIFIER
                        && classifierId.equals(constraint.getOwnerId()))
                        || (constraint.getOwnerType() == TargetType.RELATIONSHIP
                                && relationshipIds.contains(constraint.getOwnerId())))
                .count();
        return 1 + attributeRepository.findByClassifierId(classifierId).size() + operations.size() + parameters
                + constraints + relationshipIds.size() + ends;
    }

    private int relationshipCascadeCount(UUID modelId, UUID relationshipId) {
        int ends = relationshipEndRepository.findByRelationshipId(relationshipId).size();
        int constraints = (int) constraintRepository.findByModelId(modelId).stream()
                .filter(constraint -> constraint.getOwnerType() == TargetType.RELATIONSHIP
                        && relationshipId.equals(constraint.getOwnerId()))
                .count();
        return 1 + ends + constraints;
    }

    // --- shared --------------------------------------------------------------------------

    private UmlModel requireModel(UUID projectId) {
        UmlModel model = modelRepository.findByProjectId(projectId)
                .orElseThrow(() -> new com.diagramador.api.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "NOT_FOUND", "No se encontró el modelo.", List.of(), null));
        return model;
    }

    private long freshRevision(UUID modelId) {
        return modelRepository.findById(modelId)
                .orElseThrow(() -> new com.diagramador.api.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "NOT_FOUND", "No se encontró el modelo.", List.of(), null))
                .getCurrentRevision();
    }

    private record Inverse(OperationEnvelope envelope, UndoResult rejection) {

        static Inverse of(OperationEnvelope envelope) {
            return new Inverse(envelope, null);
        }

        static Inverse rejected(String code, String message) {
            return new Inverse(null, UndoResult.rejected(code, message));
        }
    }
}
