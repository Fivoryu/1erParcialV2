package com.diagramador.storage;

import com.diagramador.api.ModelResource;
import com.diagramador.api.ModelResource.RelationshipEndResource;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.PersistenceStore;
import com.diagramador.generation.InputSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Decides, per class, whether the generated backend persists it relationally or as a document. A manual
 * override always wins; otherwise the first matching signal decides and SQL is the default, so a diagram
 * without signals generates exactly what a purely relational backend would. The reason is a sentence the
 * editor and the assistant show as is.
 */
public final class PersistenceStoreClassifier {

    static final String REASON_OVERRIDE = "Set manually.";
    static final String REASON_LIST = "Has list attributes, which a document stores natively.";
    static final String REASON_DEFAULT = "No document signal, so it stays relational.";

    public record ClassifierFacts(UUID id, ClassifierKind kind, PersistenceStore override,
            boolean hasListAttribute) {
    }

    /** {@code partId} is the class at the non-composite end of a composition owned by {@code wholeId}. */
    public record CompositionLink(UUID wholeId, UUID partId) {
    }

    public record Decision(PersistenceStore store, boolean override, String reason) {
    }

    private PersistenceStoreClassifier() {
    }

    public static Map<UUID, Decision> classify(List<ClassifierFacts> classifiers, List<CompositionLink> compositions,
            Map<UUID, String> names) {
        Map<UUID, UUID> wholeByPart = new HashMap<>();
        for (CompositionLink link : compositions) {
            wholeByPart.putIfAbsent(link.partId(), link.wholeId());
        }
        Map<UUID, Decision> decisions = new LinkedHashMap<>();
        for (ClassifierFacts facts : classifiers) {
            if (facts.kind() != ClassifierKind.CLASS && facts.kind() != ClassifierKind.ABSTRACT_CLASS) {
                continue;
            }
            decisions.put(facts.id(), decide(facts, wholeByPart.get(facts.id()), names));
        }
        return decisions;
    }

    public static Map<UUID, Decision> classify(ModelResource model) {
        List<ClassifierFacts> facts = new ArrayList<>();
        Map<UUID, String> names = new HashMap<>();
        for (ModelResource.ClassifierResource classifier : model.classifiers()) {
            names.put(classifier.id(), classifier.name());
            facts.add(new ClassifierFacts(classifier.id(), classifier.kind(), classifier.storeOverride(),
                    classifier.attributes().stream().anyMatch(attribute -> isList(attribute.multiplicity()))));
        }
        List<CompositionLink> links = new ArrayList<>();
        for (ModelResource.RelationshipResource relationship : model.relationships()) {
            List<RelationshipEndResource> ends = relationship.ends();
            if (ends.size() != 2) {
                continue;
            }
            for (int index = 0; index < 2; index++) {
                if ("COMPOSITE".equalsIgnoreCase(ends.get(index).aggregationKind())) {
                    links.add(new CompositionLink(ends.get(index).classifierId(),
                            ends.get(1 - index).classifierId()));
                }
            }
        }
        return classify(facts, links, names);
    }

    public static Map<UUID, Decision> classify(InputSnapshot snapshot) {
        Set<UUID> withLists = new HashSet<>();
        snapshot.attributes().stream().filter(attribute -> isList(attribute.multiplicity()))
                .forEach(attribute -> withLists.add(attribute.classifierId()));
        List<ClassifierFacts> facts = new ArrayList<>();
        Map<UUID, String> names = new HashMap<>();
        for (InputSnapshot.ClassifierElement classifier : snapshot.classifiers()) {
            names.put(classifier.id(), classifier.name());
            facts.add(new ClassifierFacts(classifier.id(), classifier.kind(), classifier.storeOverride(),
                    withLists.contains(classifier.id())));
        }
        Map<UUID, List<InputSnapshot.RelationshipEndElement>> endsByRelationship = new LinkedHashMap<>();
        snapshot.relationshipEnds().forEach(end -> endsByRelationship
                .computeIfAbsent(end.relationshipId(), key -> new ArrayList<>()).add(end));
        List<CompositionLink> links = new ArrayList<>();
        for (List<InputSnapshot.RelationshipEndElement> ends : endsByRelationship.values()) {
            if (ends.size() != 2) {
                continue;
            }
            for (int index = 0; index < 2; index++) {
                if ("COMPOSITE".equalsIgnoreCase(ends.get(index).aggregationKind())) {
                    links.add(new CompositionLink(ends.get(index).classifierId(),
                            ends.get(1 - index).classifierId()));
                }
            }
        }
        return classify(facts, links, names);
    }

    private static Decision decide(ClassifierFacts facts, UUID wholeId, Map<UUID, String> names) {
        if (facts.override() != null) {
            return new Decision(facts.override(), true, REASON_OVERRIDE);
        }
        if (wholeId != null) {
            return new Decision(PersistenceStore.DOCUMENT, false,
                    "Part of a composition owned by " + names.getOrDefault(wholeId, "its whole") + ".");
        }
        if (facts.hasListAttribute()) {
            return new Decision(PersistenceStore.DOCUMENT, false, REASON_LIST);
        }
        return new Decision(PersistenceStore.SQL, false, REASON_DEFAULT);
    }

    private static boolean isList(MultiplicityKind multiplicity) {
        return multiplicity == MultiplicityKind.ZERO_OR_MANY || multiplicity == MultiplicityKind.ONE_OR_MANY;
    }
}
