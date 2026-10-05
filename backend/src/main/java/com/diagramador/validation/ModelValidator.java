package com.diagramador.validation;

import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.SeverityKind;
import com.diagramador.validation.ModelValidationReadModel.ClassifierElement;
import com.diagramador.validation.ModelValidationReadModel.RelationshipElement;
import com.diagramador.validation.ModelValidationReadModel.RelationshipEndElement;
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

/**
 * Pure semantic well-formedness rules over the canonical UML model read-model. Each rule method
 * returns diagnostics and performs no I/O.
 */
public class ModelValidator {

    public static final String GENERALIZATION_CYCLE = "GENERALIZATION_CYCLE";
    public static final String SELF_GENERALIZATION = "SELF_GENERALIZATION";

    /** Validates the model, returning every diagnostic ordered deterministically by element id. */
    public List<ModelValidationDiagnostic> validate(ModelValidationReadModel model) {
        List<ModelValidationDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.addAll(selfGeneralizations(model));
        diagnostics.addAll(generalizationCycles(model));
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

    private Map<UUID, String> classifierNames(ModelValidationReadModel model) {
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
