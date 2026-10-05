package com.diagramador.validation;

import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.SeverityKind;
import com.diagramador.validation.ModelValidationReadModel.AttributeElement;
import com.diagramador.validation.ModelValidationReadModel.ClassifierElement;
import com.diagramador.validation.ModelValidationReadModel.OperationElement;
import com.diagramador.validation.ModelValidationReadModel.ParameterElement;
import com.diagramador.validation.ModelValidationReadModel.RelationshipElement;
import com.diagramador.validation.ModelValidationReadModel.RelationshipEndElement;
import com.diagramador.validation.ModelValidationReadModel.TypeReferenceElement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Pure semantic well-formedness rules over the canonical UML model read-model. Each rule method
 * returns diagnostics and performs no I/O.
 */
public class ModelValidator {

    public static final String GENERALIZATION_CYCLE = "GENERALIZATION_CYCLE";
    public static final String SELF_GENERALIZATION = "SELF_GENERALIZATION";
    public static final String DUPLICATE_ROLE_NAME = "DUPLICATE_ROLE_NAME";
    public static final String INVALID_GENERALIZATION_MULTIPLICITY = "INVALID_GENERALIZATION_MULTIPLICITY";
    public static final String INCOMPLETE_MULTIPLICITY_SPECIFICATION = "INCOMPLETE_MULTIPLICITY_SPECIFICATION";
    public static final String DUPLICATE_MEMBER_NAME = "DUPLICATE_MEMBER_NAME";
    public static final String UNRESOLVED_TYPE = "UNRESOLVED_TYPE";

    /**
     * Profile primitive names resolvable at model level, mirroring the canonical V1 generation profile's
     * primitive type catalog. Model-level type resolution does not depend on the generation layer.
     */
    private static final Set<String> PROFILE_PRIMITIVE_NAMES = Set.of("String", "Integer", "Long", "Boolean",
            "Double", "BigDecimal", "LocalDate", "LocalDateTime", "UUID");

    /** Validates the model, returning every diagnostic ordered deterministically by element id. */
    public List<ModelValidationDiagnostic> validate(ModelValidationReadModel model) {
        List<ModelValidationDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.addAll(selfGeneralizations(model));
        diagnostics.addAll(generalizationCycles(model));
        diagnostics.addAll(relationshipEndValidity(model));
        diagnostics.addAll(incompleteRangeMultiplicities(model));
        diagnostics.addAll(duplicateMemberNames(model));
        diagnostics.addAll(unresolvedTypes(model));
        return diagnostics;
    }

    /**
     * R1 — a classifier must not participate in a generalization or interface-realization cycle.
     * Every classifier on a cycle is reported. Ends that do not resolve to two known classifiers
     * are ignored (they are the concern of other rules).
     */
    public List<ModelValidationDiagnostic> generalizationCycles(ModelValidationReadModel model) {
        Map<UUID, String> names = classifierNames(model);
        List<DirectedEdge> edges = directedHierarchyEdges(model, endsByRelationship(model, names.keySet()),
                names.keySet());
        Set<UUID> cyclicClassifiers = cyclicNodes(edges);
        return cyclicClassifiers.stream()
                .sorted(Comparator.comparing(UUID::toString))
                .map(classifierId -> new ModelValidationDiagnostic(GENERALIZATION_CYCLE, SeverityKind.ERROR,
                        classifierId, "generalization", "Classifier '" + names.get(classifierId)
                                + "' participates in a generalization cycle."))
                .toList();
    }

    /**
     * R2 — a classifier must not be the parent and the child of the same hierarchy relationship.
     * Relationships whose ends do not resolve to two known classifiers are ignored.
     */
    public List<ModelValidationDiagnostic> selfGeneralizations(ModelValidationReadModel model) {
        Map<UUID, String> names = classifierNames(model);
        Map<UUID, List<RelationshipEndElement>> endsByRelationship = endsByRelationship(model, names.keySet());
        List<ModelValidationDiagnostic> diagnostics = new ArrayList<>();
        for (RelationshipElement relationship : model.relationships()) {
            if (!isHierarchyKind(relationship.kind())) {
                continue;
            }
            List<RelationshipEndElement> ends = endsByRelationship.get(relationship.id());
            if (ends == null) {
                continue;
            }
            UUID firstClassifierId = ends.get(0).classifierId();
            UUID secondClassifierId = ends.get(1).classifierId();
            if (firstClassifierId == null || !firstClassifierId.equals(secondClassifierId)) {
                continue;
            }
            diagnostics.add(new ModelValidationDiagnostic(SELF_GENERALIZATION, SeverityKind.ERROR, relationship.id(),
                    feature(relationship.kind()), "Relationship '" + relationship.name() + "' of kind "
                            + relationship.kind() + " has both ends on classifier '"
                            + names.get(firstClassifierId) + "'."));
        }
        return diagnostics.stream()
                .sorted(Comparator.comparing(diagnostic -> diagnostic.elementId().toString()))
                .toList();
    }

    /**
     * R3 — validity of generalization and interface-realization ends. For hierarchy relationships whose two ends
     * resolve (each end's classifier id is known or absent): two non-null equal role names across the ends are
     * reported, and an end carrying a to-many multiplicity ({@code ONE_OR_MANY}, {@code ZERO_OR_MANY} or
     * {@code RANGE}; UML 2.5 forbids multiplicity on Generalization ends entirely) is reported with the end's
     * classifier id, or the relationship id when the end carries no classifier id.
     */
    public List<ModelValidationDiagnostic> relationshipEndValidity(ModelValidationReadModel model) {
        Map<UUID, String> names = classifierNames(model);
        Map<UUID, List<RelationshipEndElement>> pairs = resolvableEndPairs(model, names.keySet());
        List<ModelValidationDiagnostic> diagnostics = new ArrayList<>();
        for (RelationshipElement relationship : model.relationships()) {
            if (!isHierarchyKind(relationship.kind())) {
                continue;
            }
            List<RelationshipEndElement> ends = pairs.get(relationship.id());
            if (ends == null) {
                continue;
            }
            String firstRole = ends.get(0).role();
            String secondRole = ends.get(1).role();
            if (firstRole != null && firstRole.equals(secondRole)) {
                diagnostics.add(new ModelValidationDiagnostic(DUPLICATE_ROLE_NAME, SeverityKind.ERROR,
                        relationship.id(), "role", "Relationship '" + relationship.name() + "' of kind "
                                + relationship.kind() + " has both ends with role '" + firstRole + "'."));
            }
            for (RelationshipEndElement end : ends) {
                if (!isToManyMultiplicity(end.multiplicity())) {
                    continue;
                }
                UUID elementId = end.classifierId() != null ? end.classifierId() : relationship.id();
                diagnostics.add(new ModelValidationDiagnostic(INVALID_GENERALIZATION_MULTIPLICITY, SeverityKind.ERROR,
                        elementId, "multiplicity", "End with ordinal " + end.ordinal() + " of relationship '"
                                + relationship.name() + "' of kind " + relationship.kind()
                                + " carries to-many multiplicity " + end.multiplicity()
                                + ", which hierarchy ends must not have."));
            }
        }
        return sortedByElement(diagnostics);
    }

    /**
     * R4 — incomplete RANGE multiplicity specification on attributes. Deviation recorded in the acta
     * (odd/tasks/validacion-uml-semantica.md §3.1 R4 note): the canonical model does not capture RANGE lower and
     * upper bounds, so instead of comparing bounds the rule reports the RANGE as an incomplete specification.
     * Deliberately a model-level WARNING, distinct from the generation layer's UNSUPPORTED_MULTIPLICITY error.
     */
    public List<ModelValidationDiagnostic> incompleteRangeMultiplicities(ModelValidationReadModel model) {
        List<ModelValidationDiagnostic> diagnostics = new ArrayList<>();
        for (AttributeElement attribute : model.attributes()) {
            if (attribute.multiplicity() != MultiplicityKind.RANGE) {
                continue;
            }
            diagnostics.add(new ModelValidationDiagnostic(INCOMPLETE_MULTIPLICITY_SPECIFICATION, SeverityKind.WARNING,
                    attribute.classifierId(), attribute.name(), "Attribute '" + attribute.name()
                            + "' uses RANGE multiplicity, whose lower and upper bounds are not captured by the "
                            + "canonical model."));
        }
        return sortedByElement(diagnostics);
    }

    /**
     * R5 — member names must be unique per namespace. Two attributes or two operations on the same classifier
     * must not share a name (case-sensitive per UML 2.5); an attribute and an operation with the same name are
     * counted separately and do not collide.
     */
    public List<ModelValidationDiagnostic> duplicateMemberNames(ModelValidationReadModel model) {
        List<ModelValidationDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.addAll(duplicateMemberNames(model, model.attributes(), AttributeElement::classifierId,
                AttributeElement::name));
        diagnostics.addAll(duplicateMemberNames(model, model.operations(), OperationElement::classifierId,
                OperationElement::name));
        return sortedByElement(diagnostics);
    }

    /**
     * R6 — attribute and operation-parameter type references must resolve at model level, mirroring the
     * generation layer's UNRESOLVABLE_TYPE semantics without depending on it: the reference must exist, resolve
     * to exactly one classifier or primitive name, name a known profile primitive, and point to a known
     * non-package classifier.
     */
    public List<ModelValidationDiagnostic> unresolvedTypes(ModelValidationReadModel model) {
        Map<UUID, TypeReferenceElement> referencesById = new LinkedHashMap<>();
        for (TypeReferenceElement reference : model.typeReferences()) {
            referencesById.put(reference.id(), reference);
        }
        Map<UUID, ClassifierKind> kindsById = new LinkedHashMap<>();
        for (ClassifierElement classifier : model.classifiers()) {
            kindsById.put(classifier.id(), classifier.kind());
        }
        Map<UUID, UUID> classifierIdByOperationId = new LinkedHashMap<>();
        for (OperationElement operation : model.operations()) {
            classifierIdByOperationId.put(operation.id(), operation.classifierId());
        }
        List<ModelValidationDiagnostic> diagnostics = new ArrayList<>();
        for (AttributeElement attribute : model.attributes()) {
            addTypeDiagnostic(diagnostics, typeDiagnostic(attribute.typeReferenceId(), attribute.classifierId(),
                    attribute.name(), referencesById, kindsById));
        }
        for (ParameterElement parameter : model.parameters()) {
            addTypeDiagnostic(diagnostics, typeDiagnostic(parameter.typeReferenceId(),
                    classifierIdByOperationId.get(parameter.operationId()), parameter.name(), referencesById,
                    kindsById));
        }
        return sortedByElement(diagnostics);
    }

    private static void addTypeDiagnostic(List<ModelValidationDiagnostic> diagnostics,
            ModelValidationDiagnostic diagnostic) {
        if (diagnostic != null) {
            diagnostics.add(diagnostic);
        }
    }

    private static ModelValidationDiagnostic typeDiagnostic(UUID typeReferenceId, UUID ownerId, String feature,
            Map<UUID, TypeReferenceElement> referencesById, Map<UUID, ClassifierKind> kindsById) {
        TypeReferenceElement reference = typeReferenceId == null ? null : referencesById.get(typeReferenceId);
        if (reference == null) {
            return new ModelValidationDiagnostic(UNRESOLVED_TYPE, SeverityKind.ERROR, ownerId, feature,
                    "Type reference " + typeReferenceId + " cannot be resolved in the model.");
        }
        boolean referencesClassifier = reference.classifierId() != null;
        boolean referencesPrimitive = reference.primitiveName() != null;
        if (referencesClassifier == referencesPrimitive) {
            return new ModelValidationDiagnostic(UNRESOLVED_TYPE, SeverityKind.ERROR, ownerId, feature,
                    "Type reference " + typeReferenceId + " must resolve to exactly one classifier or primitive "
                            + "name.");
        }
        if (referencesPrimitive) {
            if (!PROFILE_PRIMITIVE_NAMES.contains(reference.primitiveName())) {
                return new ModelValidationDiagnostic(UNRESOLVED_TYPE, SeverityKind.ERROR, ownerId, feature,
                        "Primitive type '" + reference.primitiveName() + "' is not a known profile primitive.");
            }
            return null;
        }
        ClassifierKind kind = kindsById.get(reference.classifierId());
        if (kind == null || kind == ClassifierKind.PACKAGE) {
            return new ModelValidationDiagnostic(UNRESOLVED_TYPE, SeverityKind.ERROR, ownerId, feature,
                    "Classifier type reference " + typeReferenceId + " points to an unresolved or non-type "
                            + "classifier.");
        }
        return null;
    }

    private static <T> List<ModelValidationDiagnostic> duplicateMemberNames(ModelValidationReadModel model,
            List<T> members, Function<T, UUID> classifierId, Function<T, String> name) {
        Map<UUID, Map<String, Integer>> countsByClassifier = new LinkedHashMap<>();
        for (T member : members) {
            UUID owner = classifierId.apply(member);
            if (owner == null) {
                continue;
            }
            countsByClassifier.computeIfAbsent(owner, ignored -> new LinkedHashMap<>())
                    .merge(name.apply(member), 1, Integer::sum);
        }
        Map<UUID, String> names = classifierNames(model);
        List<ModelValidationDiagnostic> diagnostics = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Integer>> classifierEntry : countsByClassifier.entrySet()) {
            for (Map.Entry<String, Integer> nameEntry : classifierEntry.getValue().entrySet()) {
                if (nameEntry.getValue() < 2) {
                    continue;
                }
                diagnostics.add(new ModelValidationDiagnostic(DUPLICATE_MEMBER_NAME, SeverityKind.ERROR,
                        classifierEntry.getKey(), nameEntry.getKey(), "Classifier '"
                                + names.getOrDefault(classifierEntry.getKey(), "unknown") + "' has "
                                + nameEntry.getValue() + " members named '" + nameEntry.getKey() + "'."));
            }
        }
        return diagnostics;
    }

    private static boolean isToManyMultiplicity(MultiplicityKind multiplicity) {
        return multiplicity == MultiplicityKind.ONE_OR_MANY || multiplicity == MultiplicityKind.ZERO_OR_MANY
                || multiplicity == MultiplicityKind.RANGE;
    }

    /**
     * Groups the exactly-two-end collections for hierarchy relationships where every end resolves (classifier id
     * known or absent), ordered by ordinal (1 then 2). Relationships that cannot form such a pair are absent
     * from the result; unlike the cycle rules, ends without a classifier id participate so the relationship-id
     * fallback can apply.
     */
    private Map<UUID, List<RelationshipEndElement>> resolvableEndPairs(ModelValidationReadModel model,
            Set<UUID> knownClassifiers) {
        Map<UUID, List<RelationshipEndElement>> endsByRelationship = new LinkedHashMap<>();
        for (RelationshipEndElement end : model.relationshipEnds()) {
            if (end.classifierId() == null || knownClassifiers.contains(end.classifierId())) {
                endsByRelationship.computeIfAbsent(end.relationshipId(), ignored -> new ArrayList<>()).add(end);
            }
        }
        Map<UUID, List<RelationshipEndElement>> pairs = new LinkedHashMap<>();
        for (RelationshipElement relationship : model.relationships()) {
            if (!isHierarchyKind(relationship.kind())) {
                continue;
            }
            List<RelationshipEndElement> ends = endsByRelationship.get(relationship.id());
            if (ends == null || ends.size() != 2) {
                continue;
            }
            List<RelationshipEndElement> ordered = new ArrayList<>(ends);
            ordered.sort(Comparator.comparingInt(RelationshipEndElement::ordinal));
            if (ordered.get(0).ordinal() != 1 || ordered.get(1).ordinal() != 2) {
                continue;
            }
            pairs.put(relationship.id(), ordered);
        }
        return pairs;
    }

    private static List<ModelValidationDiagnostic> sortedByElement(List<ModelValidationDiagnostic> diagnostics) {
        return diagnostics.stream()
                .sorted(Comparator
                        .comparing((ModelValidationDiagnostic diagnostic) ->
                                diagnostic.elementId() == null ? "" : diagnostic.elementId().toString())
                        .thenComparing(ModelValidationDiagnostic::code)
                        .thenComparing(ModelValidationDiagnostic::feature))
                .toList();
    }

    private List<DirectedEdge> directedHierarchyEdges(ModelValidationReadModel model,
            Map<UUID, List<RelationshipEndElement>> endsByRelationship, Set<UUID> knownClassifiers) {
        List<DirectedEdge> edges = new ArrayList<>();
        for (RelationshipElement relationship : model.relationships()) {
            if (!isHierarchyKind(relationship.kind())) {
                continue;
            }
            List<RelationshipEndElement> ends = endsByRelationship.get(relationship.id());
            if (ends == null) {
                continue;
            }
            UUID subtypeId = ends.get(0).classifierId();
            UUID supertypeId = ends.get(1).classifierId();
            if (subtypeId == null || supertypeId == null || !knownClassifiers.contains(subtypeId)
                    || !knownClassifiers.contains(supertypeId)) {
                continue;
            }
            edges.add(new DirectedEdge(supertypeId, subtypeId));
        }
        return edges;
    }

    /**
     * Groups the resolved, exactly-two-end collections for hierarchy relationships, ordered so that
     * index 0 is the subtype/client end (ordinal 1) and index 1 is the supertype/supplier end
     * (ordinal 2), matching the emitter's established end convention. Relationships that cannot
     * form such a pair are absent from the result.
     */
    private Map<UUID, List<RelationshipEndElement>> endsByRelationship(ModelValidationReadModel model,
            Set<UUID> knownClassifiers) {
        Map<UUID, List<RelationshipEndElement>> endsByRelationship = new LinkedHashMap<>();
        for (RelationshipEndElement end : model.relationshipEnds()) {
            if (knownClassifiers.contains(end.classifierId())) {
                endsByRelationship.computeIfAbsent(end.relationshipId(), ignored -> new ArrayList<>()).add(end);
            }
        }
        Map<UUID, List<RelationshipEndElement>> resolvedPairs = new LinkedHashMap<>();
        for (RelationshipElement relationship : model.relationships()) {
            if (!isHierarchyKind(relationship.kind())) {
                continue;
            }
            List<RelationshipEndElement> ends = endsByRelationship.get(relationship.id());
            if (ends == null || ends.size() != 2) {
                continue;
            }
            List<RelationshipEndElement> ordered = new ArrayList<>(ends);
            ordered.sort(Comparator.comparingInt(RelationshipEndElement::ordinal));
            if (ordered.get(0).ordinal() != 1 || ordered.get(1).ordinal() != 2) {
                continue;
            }
            resolvedPairs.put(relationship.id(), ordered);
        }
        return resolvedPairs;
    }

    /** Strongly connected components (Kosaraju) over hierarchy edges; self-loops belong to R2. */
    private static Set<UUID> cyclicNodes(List<DirectedEdge> edges) {
        Map<UUID, List<UUID>> forward = new LinkedHashMap<>();
        Map<UUID, List<UUID>> backward = new LinkedHashMap<>();
        for (DirectedEdge edge : edges) {
            if (edge.supertypeId().equals(edge.subtypeId())) {
                continue;
            }
            forward.computeIfAbsent(edge.supertypeId(), ignored -> new ArrayList<>()).add(edge.subtypeId());
            backward.computeIfAbsent(edge.subtypeId(), ignored -> new ArrayList<>()).add(edge.supertypeId());
        }

        Set<UUID> visited = new HashSet<>();
        Deque<UUID> finishOrder = new ArrayDeque<>();
        for (UUID node : forward.keySet()) {
            if (!visited.contains(node)) {
                depthFirstSearch(node, forward, visited, finishOrder);
            }
        }

        Set<UUID> assigned = new HashSet<>();
        Set<UUID> cyclic = new HashSet<>();
        while (!finishOrder.isEmpty()) {
            UUID start = finishOrder.pop();
            if (assigned.contains(start)) {
                continue;
            }
            List<UUID> component = new ArrayList<>();
            depthFirstCollect(start, backward, assigned, component);
            if (component.size() > 1) {
                cyclic.addAll(component);
            }
        }
        return cyclic;
    }

    private static void depthFirstSearch(UUID start, Map<UUID, List<UUID>> adjacency, Set<UUID> visited,
            Deque<UUID> finishOrder) {
        Deque<UUID> nodes = new ArrayDeque<>();
        Deque<Iterator<UUID>> iterators = new ArrayDeque<>();
        visited.add(start);
        nodes.push(start);
        iterators.push(adjacency.getOrDefault(start, List.of()).iterator());
        while (!nodes.isEmpty()) {
            Iterator<UUID> neighbors = iterators.peek();
            if (neighbors.hasNext()) {
                UUID next = neighbors.next();
                if (visited.add(next)) {
                    nodes.push(next);
                    iterators.push(adjacency.getOrDefault(next, List.of()).iterator());
                }
            } else {
                finishOrder.push(nodes.pop());
                iterators.pop();
            }
        }
    }

    private static void depthFirstCollect(UUID start, Map<UUID, List<UUID>> adjacency, Set<UUID> visited,
            List<UUID> component) {
        Deque<UUID> nodes = new ArrayDeque<>();
        visited.add(start);
        nodes.push(start);
        while (!nodes.isEmpty()) {
            UUID node = nodes.pop();
            component.add(node);
            for (UUID next : adjacency.getOrDefault(node, List.of())) {
                if (visited.add(next)) {
                    nodes.push(next);
                }
            }
        }
    }

    private static Map<UUID, String> classifierNames(ModelValidationReadModel model) {
        Map<UUID, String> names = new HashMap<>();
        for (ClassifierElement classifier : model.classifiers()) {
            names.put(classifier.id(), classifier.name());
        }
        return names;
    }

    private static boolean isHierarchyKind(RelationshipKind kind) {
        return kind == RelationshipKind.GENERALIZATION || kind == RelationshipKind.INTERFACE_REALIZATION;
    }

    private static String feature(RelationshipKind kind) {
        return kind == RelationshipKind.INTERFACE_REALIZATION ? "interfaceRealization" : "generalization";
    }

    private record DirectedEdge(UUID supertypeId, UUID subtypeId) {
    }
}
