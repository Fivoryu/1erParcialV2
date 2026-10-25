package com.diagramador.vcs;

import com.diagramador.api.ModelResource.AttributeResource;
import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.api.ModelResource.ConnectorPathResource;
import com.diagramador.api.ModelResource.ConstraintResource;
import com.diagramador.api.ModelResource.GeometryResource;
import com.diagramador.api.ModelResource.OperationResource;
import com.diagramador.api.ModelResource.ParameterResource;
import com.diagramador.api.ModelResource.RelationshipEndResource;
import com.diagramador.api.ModelResource.RelationshipResource;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.VcsCommit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Pure three-way snapshot comparison and merge-base discovery for diagram history. */
public final class MergeDiff {

    private static final Comparator<ElementKey> ELEMENT_ORDER = Comparator.comparing(ElementKey::targetType)
            .thenComparing(ElementKey::identity);
    private static final Comparator<Conflict> CONFLICT_ORDER = Comparator.comparing(Conflict::targetType)
            .thenComparing(Conflict::targetName, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Conflict::targetId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(conflict -> conflict.field() == null ? "" : conflict.field());

    private MergeDiff() {
    }

    /**
     * Merges independent changes from source and target, retaining both sides' additions and
     * applying deletions. Conflicting elements are still represented deterministically in the
     * returned snapshot; callers must refuse to persist it while {@link Result#hasConflicts()}.
     */
    public static Result merge(SnapshotModel base, SnapshotModel source, SnapshotModel target) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");

        Map<ElementKey, Element> baseElements = flatten(base);
        Map<ElementKey, Element> sourceElements = flatten(source);
        Map<ElementKey, Element> targetElements = flatten(target);
        Set<ElementKey> allKeys = new LinkedHashSet<>();
        allKeys.addAll(baseElements.keySet());
        allKeys.addAll(sourceElements.keySet());
        allKeys.addAll(targetElements.keySet());
        List<ElementKey> orderedKeys = allKeys.stream().sorted(ELEMENT_ORDER).toList();

        Map<ElementKey, Element> merged = new LinkedHashMap<>();
        List<Conflict> conflicts = new ArrayList<>();
        for (ElementKey key : orderedKeys) {
            Element before = baseElements.get(key);
            Element fromSource = sourceElements.get(key);
            Element fromTarget = targetElements.get(key);
            if (before == null) {
                mergeAddition(key, fromSource, fromTarget, merged, conflicts);
            } else if (fromSource == null || fromTarget == null) {
                mergeDeletion(key, before, fromSource, fromTarget, merged, conflicts);
            } else {
                mergeExisting(key, before, fromSource, fromTarget, merged, conflicts);
            }
        }
        addNameCollisions(baseElements, sourceElements, targetElements, conflicts);
        conflicts.sort(CONFLICT_ORDER);
        return new Result(rebuild(merged), conflicts);
    }

    /** Finds a lowest common ancestor across both parent edges of merge commits. */
    public static Optional<VcsCommit> findMergeBase(VcsCommit sourceHead, VcsCommit targetHead) {
        if (sourceHead == null || targetHead == null || sourceHead.getId() == null || targetHead.getId() == null) {
            return Optional.empty();
        }
        Map<UUID, Ancestor> sourceAncestors = ancestors(sourceHead);
        Map<UUID, Ancestor> targetAncestors = ancestors(targetHead);
        Set<UUID> common = new LinkedHashSet<>(sourceAncestors.keySet());
        common.retainAll(targetAncestors.keySet());
        if (common.isEmpty()) {
            return Optional.empty();
        }

        List<UUID> lowest = common.stream()
                .filter(candidate -> common.stream().noneMatch(other -> !candidate.equals(other)
                        && ancestorsOf(other, sourceAncestors, targetAncestors).contains(candidate)))
                .toList();
        if (lowest.isEmpty()) {
            lowest = common.stream().toList();
        }
        return lowest.stream()
                .map(id -> new MergeBaseCandidate(sourceAncestors.get(id).commit(),
                        sourceAncestors.get(id).distance(), targetAncestors.get(id).distance()))
                .min(Comparator.comparingInt(MergeBaseCandidate::maxDistance)
                        .thenComparingInt(MergeBaseCandidate::totalDistance)
                        .thenComparing(candidate -> candidate.commit().getId().toString()))
                .map(MergeBaseCandidate::commit);
    }

    private static Set<UUID> ancestorsOf(UUID commitId, Map<UUID, Ancestor> sourceAncestors,
            Map<UUID, Ancestor> targetAncestors) {
        // The union is sufficient here: both traversals walk the same immutable commit graph.
        Set<UUID> result = new LinkedHashSet<>();
        Ancestor source = sourceAncestors.get(commitId);
        Ancestor target = targetAncestors.get(commitId);
        if (source != null) {
            result.addAll(ancestors(source.commit()).keySet());
        } else if (target != null) {
            result.addAll(ancestors(target.commit()).keySet());
        }
        return result;
    }

    private static Map<UUID, Ancestor> ancestors(VcsCommit head) {
        Map<UUID, Ancestor> ancestors = new HashMap<>();
        ArrayDeque<Ancestor> pending = new ArrayDeque<>();
        pending.add(new Ancestor(head, 0));
        while (!pending.isEmpty()) {
            Ancestor current = pending.removeFirst();
            UUID id = current.commit().getId();
            Ancestor known = ancestors.get(id);
            if (known != null && known.distance() <= current.distance()) {
                continue;
            }
            ancestors.put(id, current);
            if (current.commit().getParent() != null) {
                pending.addLast(new Ancestor(current.commit().getParent(), current.distance() + 1));
            }
            if (current.commit().getSecondParent() != null) {
                pending.addLast(new Ancestor(current.commit().getSecondParent(), current.distance() + 1));
            }
        }
        return ancestors;
    }

    private static void mergeAddition(ElementKey key, Element source, Element target,
            Map<ElementKey, Element> merged, List<Conflict> conflicts) {
        if (source == null) {
            if (target != null) {
                merged.put(key, target);
            }
            return;
        }
        if (target == null || source.fields().equals(target.fields())) {
            merged.put(key, source);
            return;
        }
        // The same identity was independently introduced with incompatible content.
        conflicts.add(conflict("NAME_COLLISION", source, null, source.valueComparedTo(target),
                target.valueComparedTo(source), null,
                "Both branches created different " + source.key().targetType() + " elements named '"
                        + source.targetName() + "'."));
        merged.put(key, source);
    }

    private static void mergeDeletion(ElementKey key, Element base, Element source, Element target,
            Map<ElementKey, Element> merged, List<Conflict> conflicts) {
        if (source == null && target == null) {
            return;
        }
        if (source == null) {
            if (base.fields().equals(target.fields())) {
                return;
            }
            conflicts.add(conflict("DELETE_MODIFY", base, base.valueComparedTo(target), null,
                    target.valueComparedTo(base), null,
                    "'" + base.targetName() + "' was deleted on source and modified on target."));
            return;
        }
        if (base.fields().equals(source.fields())) {
            return;
        }
        conflicts.add(conflict("DELETE_MODIFY", base, base.valueComparedTo(source),
                source.valueComparedTo(base), null, null,
                "'" + base.targetName() + "' was modified on source and deleted on target."));
    }

    private static void mergeExisting(ElementKey key, Element base, Element source, Element target,
            Map<ElementKey, Element> merged, List<Conflict> conflicts) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        String firstDivergentField = null;
        for (String field : base.fields().keySet()) {
            Object before = base.fields().get(field);
            Object sourceValue = source.fields().get(field);
            Object targetValue = target.fields().get(field);
            Object value;
            if (Objects.equals(sourceValue, targetValue)) {
                value = sourceValue;
            } else if (Objects.equals(sourceValue, before)) {
                value = targetValue;
            } else if (Objects.equals(targetValue, before)) {
                value = sourceValue;
            } else {
                value = sourceValue;
                if (firstDivergentField == null) {
                    firstDivergentField = field;
                }
            }
            values.put(field, value);
        }
        if (firstDivergentField != null) {
            conflicts.add(conflict("FIELD_CONFLICT", base, base.fields().get(firstDivergentField),
                    source.fields().get(firstDivergentField), target.fields().get(firstDivergentField),
                    firstDivergentField,
                    "Both branches changed '" + firstDivergentField + "' differently on "
                            + base.key().targetType() + " '" + base.targetName() + "'."));
        }
        merged.put(key, base.withFields(values));
    }

    private static void addNameCollisions(Map<ElementKey, Element> base, Map<ElementKey, Element> source,
            Map<ElementKey, Element> target, List<Conflict> conflicts) {
        List<Element> sourceAdditions = source.values().stream()
                .filter(element -> !base.containsKey(element.key()) && element.collisionName() != null)
                .sorted(Comparator.comparing((Element element) -> element.key().targetType())
                        .thenComparing(Element::collisionName)
                        .thenComparing(element -> element.targetId().toString()))
                .toList();
        List<Element> targetAdditions = target.values().stream()
                .filter(element -> !base.containsKey(element.key()) && element.collisionName() != null)
                .sorted(Comparator.comparing((Element element) -> element.key().targetType())
                        .thenComparing(Element::collisionName)
                        .thenComparing(element -> element.targetId().toString()))
                .toList();
        for (Element fromSource : sourceAdditions) {
            for (Element fromTarget : targetAdditions) {
                if (fromSource.key().equals(fromTarget.key())
                        || !fromSource.key().targetType().equals(fromTarget.key().targetType())
                        || !Objects.equals(fromSource.collisionScope(), fromTarget.collisionScope())
                        || !Objects.equals(fromSource.collisionName(), fromTarget.collisionName())
                        || fromSource.fields().equals(fromTarget.fields())) {
                    continue;
                }
                UUID targetId = fromSource.targetId().toString().compareTo(fromTarget.targetId().toString()) <= 0
                        ? fromSource.targetId() : fromTarget.targetId();
                conflicts.add(new Conflict("NAME_COLLISION", fromSource.key().targetType(), targetId,
                        fromSource.targetName(), null, null, fromSource.valueComparedTo(fromTarget),
                        fromTarget.valueComparedTo(fromSource),
                        "Both branches created different " + fromSource.key().targetType() + " elements named '"
                                + fromSource.targetName() + "'."));
            }
        }
    }

    private static Conflict conflict(String kind, Element element, Object baseValue, Object sourceValue,
            Object targetValue, String field, String message) {
        return new Conflict(kind, element.key().targetType(), element.targetId(), element.targetName(), field,
                baseValue, sourceValue, targetValue, message);
    }

    private static Map<ElementKey, Element> flatten(SnapshotModel snapshot) {
        Map<ElementKey, Element> elements = new LinkedHashMap<>();
        for (ClassifierResource classifier : snapshot.classifiers()) {
            Map<String, Object> fields = fields(
                    "name", classifier.name(), "kind", classifier.kind(), "isAbstract", classifier.isAbstract(),
                    "namespaceId", classifier.namespaceId());
            add(elements, element("CLASSIFIER", classifier.id(), classifier.name(), null, fields));
            for (AttributeResource attribute : classifier.attributes()) {
                Map<String, Object> childFields = fields(
                        "classifierId", classifier.id(), "name", attribute.name(), "kind", attribute.kind(),
                        "visibility", attribute.visibility(), "multiplicity", attribute.multiplicity(),
                        "defaultValue", attribute.defaultValue(), "typeReferenceId", attribute.typeReferenceId(),
                        "type", attribute.type(), "ordinal", attribute.ordinal());
                add(elements, element("ATTRIBUTE", attribute.id(), attribute.name(), classifier.id(), childFields));
            }
            for (OperationResource operation : classifier.operations()) {
                Map<String, Object> childFields = fields(
                        "classifierId", classifier.id(), "name", operation.name(), "visibility", operation.visibility(),
                        "isAbstract", operation.isAbstract(), "isStatic", operation.isStatic(),
                        "returnTypeReferenceId", operation.returnTypeReferenceId(), "returnType", operation.returnType(),
                        "ordinal", operation.ordinal());
                add(elements, element("OPERATION", operation.id(), operation.name(), classifier.id(), childFields));
                for (ParameterResource parameter : operation.parameters()) {
                    Map<String, Object> parameterFields = fields(
                            "operationId", operation.id(), "name", parameter.name(), "direction", parameter.direction(),
                            "typeReferenceId", parameter.typeReferenceId(), "type", parameter.type(),
                            "ordinal", parameter.ordinal());
                    add(elements, element("PARAMETER", parameter.id(), parameter.name(), operation.id(),
                            parameterFields));
                }
            }
            for (ConstraintResource constraint : classifier.constraints()) {
                Map<String, Object> constraintFields = fields(
                        "ownerType", TargetType.CLASSIFIER, "ownerId", classifier.id(),
                        "expression", constraint.expression(), "severity", constraint.severity());
                add(elements, element("CONSTRAINT", constraint.id(), constraint.expression(), classifier.id(),
                        constraintFields));
            }
        }
        for (RelationshipResource relationship : snapshot.relationships()) {
            Map<String, Object> fields = fields("kind", relationship.kind(), "name", relationship.name());
            add(elements, element("RELATIONSHIP", relationship.id(), relationship.name(), null, fields));
            for (RelationshipEndResource end : relationship.ends()) {
                Map<String, Object> endFields = fields(
                        "relationshipId", relationship.id(), "classifierId", end.classifierId(),
                        "role", end.role(), "multiplicity", end.multiplicity(), "navigability", end.navigability(),
                        "aggregationKind", end.aggregationKind(), "ordinal", end.ordinal());
                add(elements, element("RELATIONSHIP_END", end.id(), end.role(), relationship.id(), endFields));
            }
            for (ConstraintResource constraint : relationship.constraints()) {
                Map<String, Object> constraintFields = fields(
                        "ownerType", TargetType.RELATIONSHIP, "ownerId", relationship.id(),
                        "expression", constraint.expression(), "severity", constraint.severity());
                add(elements, element("CONSTRAINT", constraint.id(), constraint.expression(), relationship.id(),
                        constraintFields));
            }
        }
        for (SnapshotModel.SnapshotView view : snapshot.views()) {
            Map<String, Object> fields = fields("name", view.name(), "isDefault", view.isDefault(),
                    "viewKind", view.viewKind());
            add(elements, element("VIEW", view.id(), view.name(), null, fields));
            for (GeometryResource geometry : view.elementGeometry()) {
                Map<String, Object> geometryFields = fields(
                        "viewId", view.id(), "targetType", geometry.targetType(), "targetId", geometry.targetId(),
                        "x", geometry.x(), "y", geometry.y(), "w", geometry.w(), "h", geometry.h(),
                        "zOrder", geometry.zOrder());
                add(elements, element("GEOMETRY", geometry.id(), geometry.targetId().toString(), view.id(),
                        geometryFields));
            }
            for (ConnectorPathResource path : view.connectorPath()) {
                Map<String, Object> pathFields = fields(
                        "viewId", view.id(), "relationshipId", path.relationshipId(), "ordinal", path.ordinal(),
                        "x", path.x(), "y", path.y());
                add(elements, element("CONNECTOR_PATH", path.id(), path.relationshipId().toString(), view.id(),
                        pathFields));
            }
        }
        for (SnapshotModel.SnapshotViewStyle style : snapshot.viewStyle()) {
            Map<String, Object> styleFields = fields(
                    "viewId", style.viewId(), "targetId", style.targetId(), "routing", style.routing(),
                    "cornerRadius", style.cornerRadius(), "strokeWidth", style.strokeWidth(),
                    "dashed", style.dashed(), "routeAdjustment", style.routeAdjustment());
            String identity = style.viewId() + "/" + style.targetId();
            add(elements, new Element(new ElementKey("VIEW_STYLE", identity), style.targetId(),
                    style.viewId() + "/" + style.targetId(), null, null, new LinkedHashMap<>(styleFields)));
        }
        return elements;
    }

    private static Element element(String targetType, UUID id, String targetName, UUID scope,
            Map<String, Object> values) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>(values);
        String collisionName = collisionNameFor(targetType, fields);
        String collisionScope = switch (targetType) {
            case "CLASSIFIER" -> valueId(fields, "namespaceId");
            case "ATTRIBUTE", "OPERATION" -> scope == null ? valueId(fields, "classifierId") : scope.toString();
            case "PARAMETER" -> scope == null ? valueId(fields, "operationId") : scope.toString();
            case "RELATIONSHIP_END" -> scope == null ? valueId(fields, "relationshipId") : scope.toString();
            default -> "";
        };
        return new Element(new ElementKey(targetType, id.toString()), id, targetName, collisionScope,
                collisionName, fields);
    }

    private static void add(Map<ElementKey, Element> elements, Element element) {
        if (elements.putIfAbsent(element.key(), element) != null) {
            throw new IllegalArgumentException("Snapshot contains duplicate " + element.key().targetType()
                    + " identity " + element.key().identity());
        }
    }

    private static LinkedHashMap<String, Object> fields(Object... pairs) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            fields.put((String) pairs[index], pairs[index + 1]);
        }
        return fields;
    }

    private static SnapshotModel rebuild(Map<ElementKey, Element> elements) {
        List<Element> classifiers = ofType(elements, "CLASSIFIER");
        List<Element> relationships = ofType(elements, "RELATIONSHIP");
        List<Element> views = ofType(elements, "VIEW");

        List<ClassifierResource> mergedClassifiers = classifiers.stream().map(classifier -> {
            UUID classifierId = classifier.targetId();
            List<AttributeResource> attributes = ofType(elements, "ATTRIBUTE").stream()
                    .filter(attribute -> classifierId.equals(attribute.fields().get("classifierId")))
                    .map(MergeDiff::attributeResource).toList();
            List<OperationResource> operations = ofType(elements, "OPERATION").stream()
                    .filter(operation -> classifierId.equals(operation.fields().get("classifierId")))
                    .map(operation -> operationResource(operation, elements)).toList();
            List<ConstraintResource> constraints = ofType(elements, "CONSTRAINT").stream()
                    .filter(constraint -> TargetType.CLASSIFIER.equals(constraint.fields().get("ownerType"))
                            && classifierId.equals(constraint.fields().get("ownerId")))
                    .map(MergeDiff::constraintResource).toList();
            return new ClassifierResource(classifierId, string(classifier.fields(), "name"),
                    (ClassifierKind) classifier.fields().get("kind"), bool(classifier.fields(), "isAbstract"),
                    uuid(classifier.fields(), "namespaceId"), attributes, operations, constraints);
        }).toList();

        List<RelationshipResource> mergedRelationships = relationships.stream().map(relationship -> {
            UUID relationshipId = relationship.targetId();
            List<RelationshipEndResource> ends = ofType(elements, "RELATIONSHIP_END").stream()
                    .filter(end -> relationshipId.equals(end.fields().get("relationshipId")))
                    .map(MergeDiff::endResource).toList();
            List<ConstraintResource> constraints = ofType(elements, "CONSTRAINT").stream()
                    .filter(constraint -> TargetType.RELATIONSHIP.equals(constraint.fields().get("ownerType"))
                            && relationshipId.equals(constraint.fields().get("ownerId")))
                    .map(MergeDiff::constraintResource).toList();
            return new RelationshipResource(relationshipId, string(relationship.fields(), "kind"),
                    string(relationship.fields(), "name"), ends, constraints);
        }).toList();

        List<SnapshotModel.SnapshotView> mergedViews = views.stream().map(view -> {
            UUID viewId = view.targetId();
            List<GeometryResource> geometries = ofType(elements, "GEOMETRY").stream()
                    .filter(geometry -> viewId.equals(geometry.fields().get("viewId")))
                    .map(MergeDiff::geometryResource).toList();
            List<ConnectorPathResource> paths = ofType(elements, "CONNECTOR_PATH").stream()
                    .filter(path -> viewId.equals(path.fields().get("viewId")))
                    .map(MergeDiff::connectorPathResource).toList();
            return new SnapshotModel.SnapshotView(viewId, string(view.fields(), "name"),
                    bool(view.fields(), "isDefault"), string(view.fields(), "viewKind"), geometries, paths);
        }).toList();

        List<SnapshotModel.SnapshotViewStyle> styles = ofType(elements, "VIEW_STYLE").stream()
                .map(style -> new SnapshotModel.SnapshotViewStyle(uuid(style.fields(), "viewId"),
                        uuid(style.fields(), "targetId"), string(style.fields(), "routing"),
                        number(style.fields(), "cornerRadius"), number(style.fields(), "strokeWidth"),
                        bool(style.fields(), "dashed"), (com.fasterxml.jackson.databind.JsonNode)
                                style.fields().get("routeAdjustment")))
                .toList();
        return new SnapshotModel(mergedViews, mergedClassifiers, mergedRelationships, styles);
    }

    private static List<Element> ofType(Map<ElementKey, Element> elements, String type) {
        return elements.values().stream().filter(element -> element.key().targetType().equals(type))
                .sorted(Comparator.comparing(element -> element.key().identity())).toList();
    }

    private static AttributeResource attributeResource(Element element) {
        Map<String, Object> fields = element.fields();
        return new AttributeResource(element.targetId(), string(fields, "name"), string(fields, "kind"),
                string(fields, "visibility"), (com.diagramador.domain.MultiplicityKind) fields.get("multiplicity"),
                string(fields, "defaultValue"), uuid(fields, "typeReferenceId"), string(fields, "type"),
                number(fields, "ordinal"));
    }

    private static OperationResource operationResource(Element element, Map<ElementKey, Element> elements) {
        Map<String, Object> fields = element.fields();
        List<ParameterResource> parameters = ofType(elements, "PARAMETER").stream()
                .filter(parameter -> element.targetId().equals(parameter.fields().get("operationId")))
                .map(MergeDiff::parameterResource).toList();
        return new OperationResource(element.targetId(), string(fields, "name"), string(fields, "visibility"),
                bool(fields, "isAbstract"), bool(fields, "isStatic"), uuid(fields, "returnTypeReferenceId"),
                string(fields, "returnType"), number(fields, "ordinal"), parameters);
    }

    private static ParameterResource parameterResource(Element element) {
        Map<String, Object> fields = element.fields();
        return new ParameterResource(element.targetId(), string(fields, "name"), string(fields, "direction"),
                uuid(fields, "typeReferenceId"), string(fields, "type"), number(fields, "ordinal"));
    }

    private static ConstraintResource constraintResource(Element element) {
        return new ConstraintResource(element.targetId(), string(element.fields(), "expression"),
                string(element.fields(), "severity"));
    }

    private static RelationshipEndResource endResource(Element element) {
        Map<String, Object> fields = element.fields();
        return new RelationshipEndResource(element.targetId(), uuid(fields, "classifierId"),
                string(fields, "role"), (com.diagramador.domain.MultiplicityKind) fields.get("multiplicity"),
                (com.diagramador.domain.NavigationKind) fields.get("navigability"),
                string(fields, "aggregationKind"), (short) number(fields, "ordinal"));
    }

    private static GeometryResource geometryResource(Element element) {
        Map<String, Object> fields = element.fields();
        return new GeometryResource(element.targetId(), (TargetType) fields.get("targetType"),
                uuid(fields, "targetId"), number(fields, "x"), number(fields, "y"), number(fields, "w"),
                number(fields, "h"), number(fields, "zOrder"));
    }

    private static ConnectorPathResource connectorPathResource(Element element) {
        Map<String, Object> fields = element.fields();
        return new ConnectorPathResource(element.targetId(), uuid(fields, "relationshipId"),
                number(fields, "ordinal"), number(fields, "x"), number(fields, "y"));
    }

    private static String string(Map<String, Object> fields, String name) {
        return (String) fields.get(name);
    }

    private static UUID uuid(Map<String, Object> fields, String name) {
        return (UUID) fields.get(name);
    }

    private static int number(Map<String, Object> fields, String name) {
        return ((Number) fields.get(name)).intValue();
    }

    private static boolean bool(Map<String, Object> fields, String name) {
        return (Boolean) fields.get(name);
    }

    public record Conflict(String kind, String targetType, UUID targetId, String targetName, String field,
            Object baseValue, Object sourceValue, Object targetValue, String message) {
    }

    public record Result(SnapshotModel mergedSnapshot, List<Conflict> conflicts) {
        public Result {
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }

        public boolean hasConflicts() {
            return !conflicts.isEmpty();
        }
    }

    private record ElementKey(String targetType, String identity) {
    }

    private record Element(ElementKey key, UUID targetId, String targetName, String collisionScope,
            String collisionName, LinkedHashMap<String, Object> fields) {
        private Object valueComparedTo(Element other) {
            String primaryField = fields.containsKey("name") ? "name"
                    : fields.containsKey("role") ? "role"
                            : fields.containsKey("expression") ? "expression" : null;
            if (primaryField != null && fields.keySet().stream()
                    .filter(field -> !field.equals(primaryField))
                    .allMatch(field -> Objects.equals(fields.get(field), other.fields().get(field)))) {
                return fields.get(primaryField);
            }
            return new LinkedHashMap<>(fields);
        }

        private Element withFields(LinkedHashMap<String, Object> replacement) {
            return new Element(key, targetId, targetNameFor(key.targetType(), replacement, targetId),
                    collisionScope, collisionNameFor(key.targetType(), replacement), replacement);
        }
    }

    private static String targetNameFor(String type, Map<String, Object> fields, UUID id) {
        Object name = fields.containsKey("name") ? fields.get("name")
                : fields.containsKey("role") ? fields.get("role")
                        : fields.containsKey("expression") ? fields.get("expression") : id;
        return name == null ? String.valueOf(id) : String.valueOf(name);
    }

    private static String valueId(Map<String, Object> fields, String name) {
        return String.valueOf(fields.get(name));
    }

    private static String collisionNameFor(String type, Map<String, Object> fields) {
        return switch (type) {
            case "CLASSIFIER", "RELATIONSHIP", "VIEW", "ATTRIBUTE", "OPERATION", "PARAMETER" ->
                (String) fields.get("name");
            case "RELATIONSHIP_END" -> (String) fields.get("role");
            default -> null;
        };
    }

    private record Ancestor(VcsCommit commit, int distance) {
    }

    private record MergeBaseCandidate(VcsCommit commit, int sourceDistance, int targetDistance) {
        private int maxDistance() {
            return Math.max(sourceDistance, targetDistance);
        }

        private int totalDistance() {
            return sourceDistance + targetDistance;
        }
    }
}
