package com.diagramador.assistant;

import com.diagramador.api.ModelResource;
import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.api.ModelResource.RelationshipResource;
import com.diagramador.api.ProjectApiService;
import com.diagramador.command.Author;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.command.Precondition;
import com.diagramador.command.Target;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.UmlOperation;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.undo.UndoCaptureService;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Applies validated assistant plans through the NORMAL command executor (IA4-03, frozen design).
 * Sequential application with a stop at the first rejection; never declared atomic. Stateless:
 * operations are applied exactly as the plan endpoint returned them, revalidated server-side.
 */
@Service
public class AssistantApplyService {

    private final ProjectApiService projectApiService;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final CurrentUserContext currentUserContext;
    private final CommandService commandService;
    private final UmlAttributeRepository attributeRepository;
    private final UmlOperationRepository operationRepository;
    private final OperationParameterRepository parameterRepository;
    private final UmlConstraintRepository constraintRepository;
    private final RelationshipEndRepository relationshipEndRepository;
    private final UndoCaptureService undoCaptureService;

    public AssistantApplyService(ProjectApiService projectApiService,
            ProjectAuthorizationService projectAuthorizationService, CurrentUserContext currentUserContext,
            CommandService commandService, UmlAttributeRepository attributeRepository,
            UmlOperationRepository operationRepository, OperationParameterRepository parameterRepository,
            UmlConstraintRepository constraintRepository, RelationshipEndRepository relationshipEndRepository,
            UndoCaptureService undoCaptureService) {
        this.projectApiService = projectApiService;
        this.projectAuthorizationService = projectAuthorizationService;
        this.currentUserContext = currentUserContext;
        this.commandService = commandService;
        this.attributeRepository = attributeRepository;
        this.operationRepository = operationRepository;
        this.parameterRepository = parameterRepository;
        this.constraintRepository = constraintRepository;
        this.relationshipEndRepository = relationshipEndRepository;
        this.undoCaptureService = undoCaptureService;
    }

    public AssistantApplyResponse apply(UUID projectId, AssistantApplyRequest request) {
        ModelResource model = projectApiService.getModel(projectId);
        projectAuthorizationService.requireEditor(projectId);
        if (request.baseRevision() != model.revision()) {
            return reviewRequired(request, model.revision());
        }

        CurrentUser user = currentUserContext.require();
        Author author = new Author(user.userId(), user.username());
        List<AssistantApplyResponse.Step> steps = new ArrayList<>();
        long finalRevision = model.revision();
        int stopIndex = request.operations().size();

        for (int index = 0; index < request.operations().size(); index++) {
            AssistantApplyOp operation = request.operations().get(index);
            if (index > 0) {
                // Fresh snapshot before each step: revision re-read plus target revalidation.
                model = projectApiService.getModel(projectId);
            }
            if (operation.targetId() != null && !resolvableIds(model).contains(operation.targetId())) {
                steps.add(rejected(operation, index, diagnostic("UNKNOWN_TARGET",
                        "El destino de la operación ya no existe en el diagrama.")));
                stopIndex = index;
                break;
            }
            AssistantPlanResponse.Diagnostic duplicate = duplicateMemberNameDiagnostic(operation, model);
            if (duplicate != null) {
                steps.add(new AssistantApplyResponse.Step(index, operation.type(), operation.targetId(), "SKIPPED",
                        null, null, List.of(duplicate)));
                continue;
            }
            Mapped mapped = mapPayload(operation, model);
            if (mapped.diagnostic() != null) {
                steps.add(rejected(operation, index, mapped.diagnostic()));
                stopIndex = index;
                break;
            }

            OperationEnvelope envelope = new OperationEnvelope(UUID.randomUUID(), operation.type(), author,
                    projectId, model.id(), model.revision(), mapped.targets(), mapped.preconditions(),
                    mapped.payload(), CommandOrigin.AI, null, UUID.randomUUID().toString());
            // IA4-04: the AI path bypasses the HTTP capture hook, so it records the undo_log
            // entry itself with the same semantics: pre-state of the targets BEFORE the step.
            com.fasterxml.jackson.databind.JsonNode preSnapshot = undoCaptureService.snapshotTargets(envelope);
            CommandResult result = commandService.execute(envelope);
            if (result.status() == CommandStatus.ACCEPTED) {
                undoCaptureService.recordAccepted(envelope, result, user.userId(), preSnapshot);
                steps.add(new AssistantApplyResponse.Step(index, operation.type(), operation.targetId(), "ACCEPTED",
                        result.operationId(), result.resultRevision(), List.of()));
                finalRevision = result.resultRevision() == null ? finalRevision : result.resultRevision();
            } else {
                steps.add(rejected(operation, index, firstDiagnostic(result.diagnostics())));
                stopIndex = index;
                break;
            }
        }

        for (int index = stopIndex + 1; index < request.operations().size(); index++) {
            AssistantApplyOp operation = request.operations().get(index);
            steps.add(new AssistantApplyResponse.Step(index, operation.type(), operation.targetId(), "SKIPPED",
                    null, null, List.of()));
        }
        return new AssistantApplyResponse(steps, finalRevision);
    }

    private AssistantApplyResponse reviewRequired(AssistantApplyRequest request, long currentRevision) {
        String type = request.operations().isEmpty() ? null : request.operations().get(0).type();
        UUID targetId = request.operations().isEmpty() ? null : request.operations().get(0).targetId();
        return new AssistantApplyResponse(List.of(new AssistantApplyResponse.Step(0, type, targetId, "REJECTED",
                null, null, List.of(diagnostic("REVIEW_REQUIRED",
                        "El diagrama cambió. Revisá el modelo antes de aplicar el plan.")))),
                currentRevision);
    }

    private AssistantApplyResponse.Step rejected(AssistantApplyOp operation, int index,
            AssistantPlanResponse.Diagnostic diagnostic) {
        return new AssistantApplyResponse.Step(index, operation.type(), operation.targetId(), "REJECTED",
                null, null, List.of(diagnostic));
    }

    private static AssistantPlanResponse.Diagnostic duplicateMemberNameDiagnostic(AssistantApplyOp operation,
            ModelResource model) {
        String type = operation.type();
        if (!"attribute.add".equals(type) && !"operation.add".equals(type)) {
            return null;
        }
        Map<String, Object> args = operation.args();
        String name = text(args.get("name"));
        if (name == null) {
            return null;
        }
        UUID classifierId = resolveClassifierTarget(operation, args, model);
        if (classifierId == null) {
            return null;
        }
        ClassifierResource classifier = model.classifiers().stream()
                .filter(candidate -> classifierId.equals(candidate.id()))
                .findFirst()
                .orElse(null);
        if (classifier == null) {
            return null;
        }

        boolean duplicate = "attribute.add".equals(type)
                ? classifier.attributes().stream().anyMatch(attribute -> name.equals(attribute.name()))
                : classifier.operations().stream().anyMatch(existing -> name.equals(existing.name()));
        if (!duplicate) {
            return null;
        }
        String memberName = "attribute.add".equals(type) ? "El atributo" : "La operación";
        return diagnostic("NAME_COLLISION", memberName + " '" + name + "' ya existe en la clase '"
                + classifier.name() + "'; se omitió.");
    }

    private static Set<UUID> resolvableIds(ModelResource model) {
        Set<UUID> ids = new HashSet<>();
        for (ClassifierResource classifier : model.classifiers()) {
            ids.add(classifier.id());
            classifier.attributes().forEach(attribute -> ids.add(attribute.id()));
            classifier.operations().forEach(operation -> ids.add(operation.id()));
        }
        model.relationships().forEach(relationship -> ids.add(relationship.id()));
        return ids;
    }

    // --- Plan args -> command payload mapping -------------------------------------------

    private Mapped mapPayload(AssistantApplyOp operation, ModelResource model) {
        Map<String, Object> args = operation.args();
        return switch (operation.type()) {
            case "classifier.create" -> {
                String name = text(args.get("name"));
                if (name == null) {
                    yield missing("name", operation.type());
                }
                yield mapped(linkedPayload("id", UUID.randomUUID(), "name", name, "kind",
                        text(args.get("kind")) == null ? "CLASS" : text(args.get("kind")).toUpperCase()), List.of(),
                        List.of());
            }
            case "classifier.rename" -> {
                // Prompt v3 declares args {newName}; args.name stays a tolerated fallback.
                String name = text(args.get("newName"));
                if (name == null) {
                    name = text(args.get("name"));
                }
                if (name == null) {
                    yield missing("newName", operation.type());
                }
                yield mapped(payload("name", name), targets("classifier", operation.targetId()), List.of());
            }
            case "classifier.delete" -> mapped(new LinkedHashMap<>(), targets("classifier", operation.targetId()),
                    List.of(new Precondition("cascade_count", classifierCascadeCount(model.id(), operation.targetId()),
                            null)));
            case "classifier.setAbstract" -> {
                Object raw = args.get("isAbstract") == null ? args.get("value") : args.get("isAbstract");
                yield mapped(payload("isAbstract", raw instanceof Boolean value ? value : true),
                        targets("classifier", operation.targetId()), List.of());
            }
            case "attribute.add" -> {
                String name = text(args.get("name"));
                // DECLARED default: prompt v3 documents that attribute.add without an explicit type
                // maps to "String" (the harness contract and README record this default); no other
                // argument is guessed.
                Object type = mappedType(args.get("type"));
                if (type == null) {
                    type = Map.of("primitiveName", "String");
                }
                if (name == null) {
                    yield missing("name", operation.type());
                }
                UUID classifierId = resolveClassifierTarget(operation, args, model);
                if (classifierId == null) {
                    yield unresolvableTarget(operation.type());
                }
                Map<String, Object> payload = linkedPayload("id", UUID.randomUUID(), "name", name, "type", type,
                        "visibility", text(args.get("visibility")) == null ? "PUBLIC"
                                : text(args.get("visibility")).toUpperCase());
                if (text(args.get("multiplicity")) != null) {
                    payload.put("multiplicity", text(args.get("multiplicity")).toUpperCase());
                }
                if (text(args.get("defaultValue")) != null) {
                    payload.put("defaultValue", text(args.get("defaultValue")));
                }
                yield mapped(payload, targets("classifier", classifierId), List.of());
            }
            case "attribute.update" -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                if (text(args.get("name")) != null) {
                    payload.put("name", text(args.get("name")));
                }
                Object type = mappedType(args.get("type"));
                if (type != null) {
                    payload.put("type", type);
                }
                if (text(args.get("visibility")) != null) {
                    payload.put("visibility", text(args.get("visibility")).toUpperCase());
                }
                if (text(args.get("multiplicity")) != null) {
                    payload.put("multiplicity", text(args.get("multiplicity")).toUpperCase());
                }
                if (text(args.get("defaultValue")) != null) {
                    payload.put("defaultValue", text(args.get("defaultValue")));
                }
                if (payload.isEmpty()) {
                    yield missing("name|type|visibility|multiplicity|defaultValue", operation.type());
                }
                yield mapped(payload, targets("attribute", operation.targetId()), List.of());
            }
            case "attribute.remove" -> mapped(new LinkedHashMap<>(), targets("attribute", operation.targetId()),
                    List.of());
            case "operation.add" -> {
                String name = text(args.get("name"));
                if (name == null) {
                    yield missing("name", operation.type());
                }
                UUID classifierId = resolveClassifierTarget(operation, args, model);
                if (classifierId == null) {
                    yield unresolvableTarget(operation.type());
                }
                Map<String, Object> payload = linkedPayload("id", UUID.randomUUID(), "name", name);
                Object returnType = mappedType(args.get("returnType"));
                if (returnType != null) {
                    payload.put("returnType", returnType);
                }
                payload.put("visibility", text(args.get("visibility")) == null ? "PUBLIC"
                        : text(args.get("visibility")).toUpperCase());
                yield mapped(payload, targets("classifier", classifierId), List.of());
            }
            case "relationship.create" -> {
                UUID source = classifierEndArg(args.get("source"), model);
                UUID target = classifierEndArg(args.get("target"), model);
                if (args.get("source") == null || args.get("target") == null) {
                    yield missing("source|target", operation.type());
                }
                if (source == null || target == null) {
                    yield unresolvableTarget(operation.type());
                }
                // DECLARED default: prompt v3 documents kind ASSOCIATION when args carry no kind;
                // no other argument is guessed.
                Map<String, Object> payload = linkedPayload("id", UUID.randomUUID(),
                        "kind", text(args.get("kind")) == null ? "ASSOCIATION"
                                : text(args.get("kind")).toUpperCase(),
                        "source", Map.of("classifierId", source.toString()),
                        "target", Map.of("classifierId", target.toString()));
                if (text(args.get("name")) != null) {
                    payload.put("name", text(args.get("name")));
                }
                if (text(args.get("stereotype")) != null) {
                    payload.put("stereotype", text(args.get("stereotype")).toUpperCase());
                }
                UUID associationClass = associationClassArg(args.get("associationClass"), model);
                if (associationClass != null) {
                    payload.put("associationClassId", associationClass.toString());
                }
                if (args.get("waypoints") != null) {
                    payload.put("waypoints", args.get("waypoints"));
                }
                yield mapped(payload, List.of(new Target("classifier", source), new Target("classifier", target)),
                        List.of());
            }
            case "relationship.update" -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                if (text(args.get("name")) != null) {
                    payload.put("name", text(args.get("name")));
                }
                if (text(args.get("kind")) != null) {
                    payload.put("kind", text(args.get("kind")).toUpperCase());
                }
                if (args.containsKey("stereotype")) {
                    // A null or blank stereotype asks the command to remove it; the handler validates the rest.
                    payload.put("stereotype", text(args.get("stereotype")) == null ? null
                            : text(args.get("stereotype")).toUpperCase());
                }
                if (args.containsKey("associationClass")) {
                    // A null association class asks the command to release it; a name that does not resolve
                    // refuses the step so the plan cannot silently drop a link it could not name.
                    UUID associationClass = associationClassArg(args.get("associationClass"), model);
                    if (associationClass != null) {
                        payload.put("associationClassId", associationClass.toString());
                    } else if (args.get("associationClass") == null) {
                        payload.put("associationClassId", null);
                    } else {
                        yield unresolvableTarget(operation.type());
                    }
                }
                copyEndUpdate(payload, "source", args.get("source"));
                copyEndUpdate(payload, "target", args.get("target"));
                if (payload.isEmpty()) {
                    yield missing("name|kind|stereotype|associationClass|source|target", operation.type());
                }
                yield mapped(payload, targets("relationship", operation.targetId()), List.of());
            }
            case "relationship.delete" -> mapped(new LinkedHashMap<>(),
                    targets("relationship", operation.targetId()),
                    List.of(new Precondition("cascade_count",
                            relationshipCascadeCount(model.id(), operation.targetId()), null)));
            default -> mapped(new LinkedHashMap<>(), List.of(), List.of());
        };
    }

    private void copyEndUpdate(Map<String, Object> payload, String field, Object raw) {
        if (!(raw instanceof Map<?, ?> end)) {
            return;
        }
        Map<String, Object> update = new LinkedHashMap<>();
        for (String allowed : List.of("role", "multiplicity", "navigability")) {
            if (end.get(allowed) != null) {
                update.put(allowed, allowed.equals("role") ? end.get(allowed)
                        : String.valueOf(end.get(allowed)).toUpperCase());
            }
        }
        if (!update.isEmpty()) {
            payload.put(field, update);
        }
    }

    /**
     * Resolves the owner classifier of an attribute/operation add. Plans validated with forward
     * simulation carry {@code targetClassifierName} for owners created earlier in the same plan;
     * the apply loop re-reads the model before each step, so the planned classifier exists by then
     * and is resolved by (accent/case-insensitive) name.
     */
    private static UUID resolveClassifierTarget(AssistantApplyOp operation, Map<String, Object> args,
            ModelResource model) {
        if (operation.targetId() != null) {
            return operation.targetId();
        }
        return classifierIdByName(model, text(args.get("targetClassifierName")));
    }

    /** The class named as an association class, as {"classifierName": "..."} or as the bare name. */
    private static UUID associationClassArg(Object raw, ModelResource model) {
        return raw instanceof String name ? classifierIdByName(model, name) : classifierEndArg(raw, model);
    }

    private static UUID classifierEndArg(Object raw, ModelResource model) {
        if (!(raw instanceof Map<?, ?> end)) {
            return null;
        }
        if (end.get("classifierId") != null) {
            try {
                return UUID.fromString(String.valueOf(end.get("classifierId")));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if (end.get("classifierName") instanceof String name) {
            return classifierIdByName(model, name);
        }
        return null;
    }

    private static UUID classifierIdByName(ModelResource model, String name) {
        if (name == null) {
            return null;
        }
        UUID match = null;
        for (ClassifierResource classifier : model.classifiers()) {
            if (sameName(classifier.name(), name)) {
                if (match != null) {
                    return null;
                }
                match = classifier.id();
            }
        }
        return match;
    }

    private static Mapped unresolvableTarget(String type) {
        return new Mapped(null, List.of(), List.of(),
                diagnostic("UNKNOWN_TARGET",
                        "El destino de la operación " + type + " ya no existe en el diagrama."));
    }

    private static boolean sameName(String left, String right) {
        return left != null && right != null
                && normalizeName(left).equals(normalizeName(right));
    }

    private static String normalizeName(String value) {
        String decomposed = Normalizer.normalize(value.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return Pattern.compile("\\p{M}+").matcher(decomposed).replaceAll("");
    }

    private static Object mappedType(Object raw) {
        if (raw instanceof String primitiveName && !primitiveName.isBlank()) {
            return Map.of("primitiveName", primitiveName.trim());
        }
        if (raw instanceof Map<?, ?> type) {
            if (type.get("primitiveName") instanceof String primitiveName && !primitiveName.isBlank()) {
                return Map.of("primitiveName", primitiveName);
            }
            if (type.get("classifierId") != null) {
                return Map.of("classifierId", String.valueOf(type.get("classifierId")));
            }
        }
        return null;
    }

    // --- Cascade preconditions (CommandService requires cascade_count for multi-deletions) --

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

    // --- helpers ------------------------------------------------------------------------

    private static Mapped mapped(Object payload, List<Target> targets, List<Precondition> preconditions) {
        return new Mapped(payload, targets, preconditions, null);
    }

    private static Mapped missing(String argument, String type) {
        return new Mapped(null, List.of(), List.of(),
                diagnostic("MISSING_ARGUMENT", "Falta el argumento requerido '" + argument + "' para " + type + "."));
    }

    private static Map<String, Object> payload(Object... keyValues) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            payload.put((String) keyValues[index], keyValues[index + 1]);
        }
        return payload;
    }

    private static Map<String, Object> linkedPayload(Object... keyValues) {
        return payload(keyValues);
    }

    private static List<Target> targets(String scope, UUID id) {
        return List.of(new Target(scope, id));
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string.trim() : null;
    }

    private static AssistantPlanResponse.Diagnostic diagnostic(String code, String message) {
        return new AssistantPlanResponse.Diagnostic(code, message);
    }

    private static AssistantPlanResponse.Diagnostic firstDiagnostic(
            List<com.diagramador.command.Diagnostic> diagnostics) {
        if (diagnostics.isEmpty()) {
            return diagnostic("MODEL_INVALID", "El comando fue rechazado sin diagnóstico.");
        }
        return diagnostic(diagnostics.get(0).code().name(), diagnostics.get(0).message());
    }

    private record Mapped(Object payload, List<Target> targets, List<Precondition> preconditions,
            AssistantPlanResponse.Diagnostic diagnostic) {
    }
}
