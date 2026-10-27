package com.diagramador.vcs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Field-level, read-only comparison of two snapshots, the data behind the commit and merge-request diffs. It reuses
 * the per-field view a merge compares ({@link MergeDiff#diffElements}) so "what changed" means the same in both.
 */
final class SnapshotDiff {

    private static final List<String> TYPE_ORDER = List.of("CLASSIFIER", "ATTRIBUTE", "OPERATION", "PARAMETER",
            "CONSTRAINT", "RELATIONSHIP", "RELATIONSHIP_END", "VIEW", "GEOMETRY", "CONNECTOR_PATH", "VIEW_STYLE");

    private SnapshotDiff() {
    }

    /** Changes from {@code before} (null for the first commit) to {@code after}, grouped by element type. */
    static List<VcsResources.ElementChange> between(SnapshotModel before, SnapshotModel after) {
        Map<String, MergeDiff.DiffElement> oldElements = before == null ? Map.of() : MergeDiff.diffElements(before);
        Map<String, MergeDiff.DiffElement> newElements = MergeDiff.diffElements(after);

        Set<String> keys = new LinkedHashSet<>(oldElements.keySet());
        keys.addAll(newElements.keySet());
        List<String> ordered = keys.stream()
                .sorted(Comparator
                        .comparingInt((String key) -> typeRank(typeOf(key, oldElements, newElements)))
                        .thenComparing(key -> nameOf(key, oldElements, newElements).toLowerCase())
                        .thenComparing(Comparator.naturalOrder()))
                .toList();

        List<VcsResources.ElementChange> changes = new ArrayList<>();
        for (String key : ordered) {
            MergeDiff.DiffElement oldElement = oldElements.get(key);
            MergeDiff.DiffElement newElement = newElements.get(key);
            if (oldElement == null) {
                changes.add(change("ADDED", newElement, ownerName(newElement, newElements, oldElements),
                        fieldsOf(null, newElement, newElements, oldElements)));
            } else if (newElement == null) {
                changes.add(change("REMOVED", oldElement, ownerName(oldElement, oldElements, newElements),
                        fieldsOf(oldElement, null, newElements, oldElements)));
            } else {
                List<VcsResources.FieldChange> fields = fieldsOf(oldElement, newElement, newElements, oldElements);
                if (!fields.isEmpty()) {
                    changes.add(change("MODIFIED", newElement, ownerName(newElement, newElements, oldElements),
                            fields));
                }
            }
        }
        return List.copyOf(changes);
    }

    private static VcsResources.ElementChange change(String kind, MergeDiff.DiffElement element, String ownerName,
            List<VcsResources.FieldChange> fields) {
        return new VcsResources.ElementChange(kind, element.targetType(), element.targetId(), element.targetName(),
                ownerName, fields);
    }

    /** The visible fields that differ; for an added or removed element, all its visible fields. */
    private static List<VcsResources.FieldChange> fieldsOf(MergeDiff.DiffElement oldElement,
            MergeDiff.DiffElement newElement, Map<String, MergeDiff.DiffElement> newElements,
            Map<String, MergeDiff.DiffElement> oldElements) {
        MergeDiff.DiffElement reference = newElement != null ? newElement : oldElement;
        Map<String, Object> oldFields = oldElement == null ? Map.of() : oldElement.fields();
        Map<String, Object> newFields = newElement == null ? Map.of() : newElement.fields();
        List<VcsResources.FieldChange> fields = new ArrayList<>();
        for (String field : reference.fields().keySet()) {
            String label = labelOf(reference.targetType(), field);
            if (label == null) {
                continue;
            }
            Object before = oldFields.get(field);
            Object after = newFields.get(field);
            if (oldElement != null && newElement != null && Objects.equals(before, after)) {
                continue;
            }
            if (oldElement == null && after == null || newElement == null && before == null) {
                continue;
            }
            fields.add(new VcsResources.FieldChange(label,
                    display(reference.targetType(), field, before, oldElements, newElements),
                    display(reference.targetType(), field, after, newElements, oldElements)));
        }
        return List.copyOf(fields);
    }

    /**
     * The label a person reads for a field, or null for internal wiring (identifiers, ordering) that is not part of a
     * diff. Two identifiers are shown because they carry meaning: where a relationship end points to, and the
     * association class of a relationship; they are rendered as the element's name.
     */
    private static String labelOf(String targetType, String field) {
        if (field.equals("classifierId") && targetType.equals("RELATIONSHIP_END")) {
            return "classifier";
        }
        if (field.equals("associationClassId")) {
            return "associationClass";
        }
        if (field.endsWith("Id") || field.equals("ordinal") || field.equals("ownerType")) {
            return null;
        }
        return field;
    }

    private static String display(String targetType, String field, Object value,
            Map<String, MergeDiff.DiffElement> primary, Map<String, MergeDiff.DiffElement> fallback) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID id && (field.equals("classifierId") || field.equals("associationClassId"))) {
            String name = nameById("CLASSIFIER:" + id, primary, fallback);
            return name == null ? id.toString() : name;
        }
        return String.valueOf(value);
    }

    private static String ownerName(MergeDiff.DiffElement element, Map<String, MergeDiff.DiffElement> primary,
            Map<String, MergeDiff.DiffElement> fallback) {
        Map<String, Object> fields = element.fields();
        String ownerKey = switch (element.targetType()) {
            case "ATTRIBUTE", "OPERATION" -> "CLASSIFIER:" + fields.get("classifierId");
            case "PARAMETER" -> "OPERATION:" + fields.get("operationId");
            case "RELATIONSHIP_END" -> "RELATIONSHIP:" + fields.get("relationshipId");
            case "GEOMETRY", "CONNECTOR_PATH" -> "VIEW:" + fields.get("viewId");
            case "CONSTRAINT" -> fields.get("ownerType") + ":" + fields.get("ownerId");
            default -> null;
        };
        return ownerKey == null ? null : nameById(ownerKey, primary, fallback);
    }

    private static String nameById(String key, Map<String, MergeDiff.DiffElement> primary,
            Map<String, MergeDiff.DiffElement> fallback) {
        MergeDiff.DiffElement element = primary.get(key);
        if (element == null) {
            element = fallback.get(key);
        }
        return element == null ? null : element.targetName();
    }

    private static String typeOf(String key, Map<String, MergeDiff.DiffElement> oldElements,
            Map<String, MergeDiff.DiffElement> newElements) {
        MergeDiff.DiffElement element = newElements.containsKey(key) ? newElements.get(key) : oldElements.get(key);
        return element.targetType();
    }

    private static String nameOf(String key, Map<String, MergeDiff.DiffElement> oldElements,
            Map<String, MergeDiff.DiffElement> newElements) {
        MergeDiff.DiffElement element = newElements.containsKey(key) ? newElements.get(key) : oldElements.get(key);
        return element.targetName() == null ? "" : element.targetName();
    }

    private static int typeRank(String type) {
        int index = TYPE_ORDER.indexOf(type);
        return index < 0 ? TYPE_ORDER.size() : index;
    }
}
