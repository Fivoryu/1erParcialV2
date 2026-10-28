package com.diagramador.assistant;

import com.diagramador.api.ModelResource;
import com.diagramador.api.ModelResource.AttributeResource;
import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.api.ModelResource.OperationResource;
import com.diagramador.api.ModelResource.RelationshipEndResource;
import com.diagramador.api.ModelResource.RelationshipResource;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AssistantPlanValidator {

    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");
    private static final String UNKNOWN_OPERATION = "UNKNOWN_OPERATION";
    private static final String AMBIGUOUS_TARGET = "AMBIGUOUS_TARGET";
    private static final String UNKNOWN_TARGET = "UNKNOWN_TARGET";
    private static final String NAME_COLLISION = "NAME_COLLISION";

    public AssistantPlanResponse validate(String draftIntent, List<Map<String, Object>> rawOperations,
            ModelResource model, String question, String explanation) {
        String intent = normalizedIntent(draftIntent);
        List<Map<String, Object>> input = rawOperations == null ? List.of() : rawOperations;
        List<AssistantPlanOperation> accepted = new ArrayList<>();
        List<AssistantPlanResponse.Diagnostic> diagnostics = new ArrayList<>();
        boolean allUnknown = !input.isEmpty();
        boolean collisionSeen = false;
        boolean forcedReject = false;
        String ambiguityQuestion = null;
        // Forward simulation: classifier.create operations accepted EARLIER in this same draft
        // make their names resolvable targets for later attribute/relationship operations.
        Map<String, String> plannedCreates = new LinkedHashMap<>();

        for (Map<String, Object> raw : input) {
            String type = text(raw == null ? null : raw.get("type"));
            String targetName = text(raw == null ? null : raw.get("targetName"));
            if (!AssistantPlanCatalog.contains(type)) {
                diagnostics.add(diagnostic(UNKNOWN_OPERATION,
                        "La operación solicitada no pertenece al catálogo permitido."));
                if ("operation.remove".equals(type)) {
                    List<Candidate> matches = operationMatches(targetName, model);
                    if (matches.isEmpty()) {
                        diagnostics.add(diagnostic(UNKNOWN_TARGET,
                                "No se encontró el objetivo indicado en el diagrama."));
                        forcedReject = true;
                    } else if (matches.size() > 1) {
                        diagnostics.add(diagnostic(AMBIGUOUS_TARGET,
                                "El nombre coincide con más de un elemento del diagrama."));
                        ambiguityQuestion = ambiguityQuestion(matches);
                    }
                }
                continue;
            }
            allUnknown = false;

            if ("classifier.create".equals(type)) {
                if (targetName == null || targetName.isBlank()) {
                    diagnostics.add(diagnostic(UNKNOWN_TARGET,
                            "No se encontró el nombre de la clase que se quiere crear."));
                    forcedReject = true;
                    continue;
                }
                if (model.classifiers().stream().anyMatch(classifier -> same(classifier.name(), targetName))
                        || plannedCreates.containsKey(normalize(targetName))) {
                    diagnostics.add(diagnostic(NAME_COLLISION, "Ya existe una clase con ese nombre."));
                    collisionSeen = true;
                    continue;
                }
                Map<String, Object> args = args(raw);
                args.put("name", targetName);
                accepted.add(new AssistantPlanOperation(type, null, args));
                plannedCreates.put(normalize(targetName), targetName);
                continue;
            }

            if ("attribute.add".equals(type) || "operation.add".equals(type)) {
                OwnerAndName target = ownerAndName(targetName);
                List<Candidate> owners = target == null ? List.of() : classifierMatches(target.owner(), model);
                Candidate owner = uniqueTarget(owners, diagnostics);
                if (owner == null && target != null && owners.isEmpty()
                        && plannedCreates.containsKey(normalize(target.owner()))) {
                    // Owner is created earlier in this plan: carry the planned name so apply
                    // resolves the (by then existing) classifier by name at step time.
                    Map<String, Object> args = args(raw);
                    args.put("name", target.name());
                    args.put("targetClassifierName", target.owner());
                    accepted.add(new AssistantPlanOperation(type, null, args));
                    continue;
                }
                if (owner == null) {
                    if (owners.isEmpty()) {
                        diagnostics.add(diagnostic(UNKNOWN_TARGET,
                                "No se encontró el objetivo indicado en el diagrama."));
                        forcedReject = true;
                    } else {
                        ambiguityQuestion = ambiguityQuestion(owners);
                    }
                    continue;
                }
                Map<String, Object> args = args(raw);
                args.put("name", target.name());
                accepted.add(new AssistantPlanOperation(type, owner.id(), args));
                continue;
            }

            if ("relationship.create".equals(type)) {
                NamePair pair = namePair(targetName);
                if (pair == null) {
                    diagnostics.add(diagnostic(UNKNOWN_TARGET,
                            "No se encontró el objetivo indicado en el diagrama."));
                    forcedReject = true;
                    continue;
                }
                List<Candidate> sourceMatches = classifierMatches(pair.first(), model);
                List<Candidate> targetMatches = classifierMatches(pair.second(), model);
                Candidate source = uniqueTarget(sourceMatches, diagnostics);
                Candidate target = uniqueTarget(targetMatches, diagnostics);
                boolean sourcePlanned = source == null && sourceMatches.isEmpty()
                        && plannedCreates.containsKey(normalize(pair.first()));
                boolean targetPlanned = target == null && targetMatches.isEmpty()
                        && plannedCreates.containsKey(normalize(pair.second()));
                if ((source != null || sourcePlanned) && (target != null || targetPlanned)) {
                    Map<String, Object> args = args(raw);
                    args.put("source", source != null ? Map.of("classifierId", source.id())
                            : Map.of("classifierName", pair.first()));
                    args.put("target", target != null ? Map.of("classifierId", target.id())
                            : Map.of("classifierName", pair.second()));
                    accepted.add(new AssistantPlanOperation(type, null, args));
                    continue;
                }
                if ((sourceMatches.isEmpty() && !sourcePlanned) || (targetMatches.isEmpty() && !targetPlanned)) {
                    diagnostics.add(diagnostic(UNKNOWN_TARGET,
                            "No se encontró el objetivo indicado en el diagrama."));
                    forcedReject = true;
                }
                if (sourceMatches.size() > 1) {
                    ambiguityQuestion = ambiguityQuestion(sourceMatches);
                } else if (targetMatches.size() > 1) {
                    ambiguityQuestion = ambiguityQuestion(targetMatches);
                }
                continue;
            }

            List<Candidate> matches = switch (type) {
                case "classifier.rename", "classifier.delete", "classifier.setAbstract", "classifier.move" ->
                    classifierMatches(targetName, model);
                case "attribute.update", "attribute.remove" -> attributeMatches(targetName, model);
                case "relationship.update", "relationship.delete" -> relationshipMatches(targetName, model);
                default -> List.of();
            };
            Candidate target = uniqueTarget(matches, diagnostics);
            if (target == null) {
                if (matches.isEmpty()) {
                    diagnostics.add(diagnostic(UNKNOWN_TARGET,
                            "No se encontró el objetivo indicado en el diagrama."));
                    forcedReject = true;
                } else {
                    ambiguityQuestion = ambiguityQuestion(matches);
                }
                continue;
            }
            accepted.add(new AssistantPlanOperation(type, target.id(), args(raw)));
        }

        if (forcedReject) {
            intent = "REJECT";
            accepted.clear();
            question = null;
        } else if (ambiguityQuestion != null) {
            intent = "CLARIFY";
            accepted.clear();
            question = ambiguityQuestion;
        } else if ("PLAN".equals(intent) && ((allUnknown && !input.isEmpty())
                || (collisionSeen && accepted.isEmpty()))) {
            intent = "REJECT";
            accepted.clear();
        } else if (!"PLAN".equals(intent)) {
            accepted.clear();
        }

        return new AssistantPlanResponse(intent, accepted, question, explanation, diagnostics);
    }

    private static Candidate uniqueTarget(List<Candidate> matches,
            List<AssistantPlanResponse.Diagnostic> diagnostics) {
        if (matches.size() > 1) {
            diagnostics.add(diagnostic(AMBIGUOUS_TARGET,
                    "El nombre coincide con más de un elemento del diagrama."));
            return null;
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static List<Candidate> classifierMatches(String name, ModelResource model) {
        if (name == null || model == null) {
            return List.of();
        }
        return model.classifiers().stream()
                .filter(classifier -> same(classifier.name(), name))
                .map(classifier -> new Candidate(classifier.id(), classifier.name()))
                .sorted(CANDIDATE_ORDER)
                .toList();
    }

    private static List<Candidate> attributeMatches(String name, ModelResource model) {
        if (name == null || model == null) {
            return List.of();
        }
        List<Candidate> matches = new ArrayList<>();
        for (ClassifierResource classifier : model.classifiers()) {
            for (AttributeResource attribute : classifier.attributes()) {
                String label = classifier.name() + "." + attribute.name();
                if (same(label, name)) {
                    matches.add(new Candidate(attribute.id(), label));
                }
            }
        }
        return sorted(matches);
    }

    private static List<Candidate> operationMatches(String name, ModelResource model) {
        if (name == null || model == null) {
            return List.of();
        }
        List<Candidate> matches = new ArrayList<>();
        for (ClassifierResource classifier : model.classifiers()) {
            for (OperationResource operation : classifier.operations()) {
                String label = classifier.name() + "." + operation.name();
                if (same(label, name)) {
                    matches.add(new Candidate(operation.id(), label));
                }
            }
        }
        return sorted(matches);
    }

    private static List<Candidate> relationshipMatches(String name, ModelResource model) {
        NamePair requested = namePair(name);
        if (requested == null || model == null) {
            return List.of();
        }
        Map<UUID, String> classifierNames = new LinkedHashMap<>();
        for (ClassifierResource classifier : model.classifiers()) {
            classifierNames.put(classifier.id(), classifier.name());
        }
        List<Candidate> matches = new ArrayList<>();
        for (RelationshipResource relationship : model.relationships()) {
            if (relationship.ends().size() < 2) {
                continue;
            }
            RelationshipEndResource first = relationship.ends().get(0);
            RelationshipEndResource second = relationship.ends().get(1);
            String source = classifierNames.get(first.classifierId());
            String target = classifierNames.get(second.classifierId());
            if (samePair(requested, source, target)) {
                matches.add(new Candidate(relationship.id(), source + "->" + target));
            }
        }
        return sorted(matches);
    }

    private static boolean samePair(NamePair pair, String source, String target) {
        if (source == null || target == null) {
            return false;
        }
        return (same(pair.first(), source) && same(pair.second(), target))
                || (same(pair.first(), target) && same(pair.second(), source));
    }

    private static OwnerAndName ownerAndName(String targetName) {
        if (targetName == null) {
            return null;
        }
        int separator = targetName.lastIndexOf('.');
        if (separator <= 0 || separator == targetName.length() - 1) {
            return null;
        }
        return new OwnerAndName(targetName.substring(0, separator).trim(), targetName.substring(separator + 1).trim());
    }

    private static NamePair namePair(String value) {
        if (value == null) {
            return null;
        }
        String[] names = value.split("->", -1);
        if (names.length != 2 || names[0].isBlank() || names[1].isBlank()) {
            return null;
        }
        return new NamePair(names[0].trim(), names[1].trim());
    }

    private static Map<String, Object> args(Map<String, Object> raw) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (raw != null) {
            if (raw.get("args") instanceof Map<?, ?> nestedArgs) {
                nestedArgs.forEach((key, value) -> {
                    if (key instanceof String stringKey) {
                        args.put(stringKey, value);
                    }
                });
            }
            raw.forEach((key, value) -> {
                if (!"type".equals(key) && !"targetName".equals(key) && !"args".equals(key)) {
                    args.put(key, value);
                }
            });
        }
        return args;
    }

    private static String normalizedIntent(String intent) {
        if (intent == null) {
            return "REJECT";
        }
        return switch (intent.trim().toUpperCase(Locale.ROOT)) {
            case "PLAN", "EXPLAIN", "CLARIFY", "REJECT" -> intent.trim().toUpperCase(Locale.ROOT);
            default -> "REJECT";
        };
    }

    private static String text(Object value) {
        return value instanceof String string ? string.trim() : null;
    }

    private static boolean same(String left, String right) {
        return left != null && right != null && normalize(left).equals(normalize(right));
    }

    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(value.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return ACCENTS.matcher(decomposed).replaceAll("");
    }

    private static String ambiguityQuestion(List<Candidate> candidates) {
        List<Candidate> ordered = sorted(candidates);
        return "¿A cuál te referís: " + String.join(" o ", ordered.stream().map(Candidate::name).toList()) + "?";
    }

    private static List<Candidate> sorted(List<Candidate> candidates) {
        return candidates.stream().sorted(CANDIDATE_ORDER).toList();
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparing((Candidate candidate) -> normalize(candidate.name()))
            .thenComparing(Candidate::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Candidate::name);

    private static AssistantPlanResponse.Diagnostic diagnostic(String code, String message) {
        return new AssistantPlanResponse.Diagnostic(code, message);
    }

    private record Candidate(UUID id, String name) {
    }

    private record NamePair(String first, String second) {
    }

    private record OwnerAndName(String owner, String name) {
    }
}
