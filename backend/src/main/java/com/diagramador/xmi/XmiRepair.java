package com.diagramador.xmi;

import com.diagramador.api.ModelResource;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.TargetType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic repairs for problems that have exactly one correct answer. Each repair is returned as a named
 * {@link Repair} and added to the reading's losses, so nothing is silent. Choices (name collisions) are not
 * repaired here: they stay blockers with options and are answered through {@link XmiResolution}.
 */
public class XmiRepair {

    public static final int MIN_WIDTH = 40;
    public static final int MIN_HEIGHT = 30;
    static final String DEFAULT_VIEW_NAME = "Diagrama importado";

    private static final int GRID_COLUMNS = 4;

    /** A repair that was applied, with a Spanish description and how many constructs it touched. */
    public record Repair(String code, String subject, String message, int count) {
    }

    public record Result(XmiReadingResult reading, List<Repair> repairs) {

        public Result {
            repairs = List.copyOf(repairs);
        }
    }

    public Result repair(XmiReadingResult reading) {
        Draft draft = new Draft(reading);
        draft.regenerateDuplicateClassifierIds();
        draft.replaceBlankNames();
        draft.hoistNamespaces();
        draft.reduceToOneView();
        draft.dropDangling();
        draft.normalizeGeometry();
        return draft.finish();
    }

    /** Mutable working copy shared with the resolution applier; every step records what it changed. */
    static final class Draft {

        private final XmiReadingResult source;
        private List<ModelResource.ClassifierResource> classifiers;
        private List<ModelResource.RelationshipResource> relationships;
        private List<ModelResource.ViewResource> views;
        private List<XmiReadingResult.Diagram> diagrams;
        private final Map<String, Repair> repairs = new LinkedHashMap<>();
        private final Map<String, Integer> losses = new LinkedHashMap<>();

        Draft(XmiReadingResult source) {
            this.source = source;
            this.classifiers = new ArrayList<>(source.model().classifiers());
            this.relationships = new ArrayList<>(source.model().relationships());
            this.views = new ArrayList<>(source.model().views());
            this.diagrams = new ArrayList<>(source.diagrams());
        }

        List<ModelResource.ClassifierResource> classifiers() {
            return classifiers;
        }

        void setClassifiers(List<ModelResource.ClassifierResource> value) {
            classifiers = new ArrayList<>(value);
        }

        List<ModelResource.ViewResource> views() {
            return views;
        }

        List<XmiReadingResult.Diagram> diagrams() {
            return diagrams;
        }

        void setViews(List<ModelResource.ViewResource> viewList, List<XmiReadingResult.Diagram> diagramList) {
            views = new ArrayList<>(viewList);
            diagrams = new ArrayList<>(diagramList);
        }

        /** Records one applied repair; repeated codes accumulate, and a loss is added when data was dropped. */
        void record(String code, String subject, String message, int count, boolean loss) {
            Repair previous = repairs.get(code);
            int total = (previous == null ? 0 : previous.count()) + count;
            repairs.put(code, new Repair(code, previous == null ? subject : previous.subject(),
                    message.replace("{n}", String.valueOf(total)), total));
            if (loss) {
                losses.merge(code, count, Integer::sum);
            }
        }

        Result finish() {
            if (repairs.isEmpty()) {
                return new Result(source, List.of());
            }
            ModelResource model = source.model();
            ModelResource repaired = new ModelResource(model.id(), model.projectId(), model.revision(),
                    model.versionId(), views, classifiers, relationships, model.viewStyle());
            Map<String, Integer> merged = new LinkedHashMap<>();
            source.losses().forEach(loss -> merged.merge(loss.name(), loss.count(), Integer::sum));
            losses.forEach((name, count) -> merged.merge(name, count, Integer::sum));
            List<XmiReadingResult.Loss> lossList = merged.entrySet().stream()
                    .map(entry -> new XmiReadingResult.Loss(entry.getKey(), entry.getValue())).toList();
            return new Result(new XmiReadingResult(repaired, diagrams, source.documentation(),
                    source.disagreements(), lossList), List.copyOf(repairs.values()));
        }

        void regenerateDuplicateClassifierIds() {
            Set<UUID> seen = new HashSet<>();
            classifiers.forEach(item -> {
                if (item.id() != null) {
                    seen.add(item.id());
                }
            });
            Set<UUID> kept = new HashSet<>();
            List<ModelResource.ClassifierResource> result = new ArrayList<>();
            int regenerated = 0;
            for (ModelResource.ClassifierResource item : classifiers) {
                if (item.id() == null || kept.add(item.id())) {
                    result.add(item);
                    continue;
                }
                int occurrence = 1;
                UUID fresh = derived("classifier:" + item.id() + ":" + occurrence);
                while (seen.contains(fresh)) {
                    fresh = derived("classifier:" + item.id() + ":" + ++occurrence);
                }
                seen.add(fresh);
                kept.add(fresh);
                result.add(new ModelResource.ClassifierResource(fresh, item.name(), item.kind(), item.isAbstract(),
                        item.namespaceId(), item.attributes(), item.operations(), item.constraints(),
                        item.storeOverride(), item.store(), item.storeReason()));
                regenerated++;
            }
            if (regenerated > 0) {
                classifiers = result;
                record("duplicate_classifier_ids_regenerated", null,
                        "{n} clase(s) repetían el identificador de otra; se les asignó uno nuevo.", regenerated,
                        true);
            }
        }

        void replaceBlankNames() {
            Set<String> used = new HashSet<>();
            classifiers.forEach(item -> {
                if (item.name() != null && !item.name().isBlank()) {
                    used.add(item.name());
                }
            });
            List<ModelResource.ClassifierResource> result = new ArrayList<>();
            int counter = 0;
            int replaced = 0;
            for (ModelResource.ClassifierResource item : classifiers) {
                if (item.name() != null && !item.name().isBlank()) {
                    result.add(item);
                    continue;
                }
                String placeholder;
                do {
                    placeholder = "SinNombre-" + ++counter;
                } while (used.contains(placeholder));
                used.add(placeholder);
                result.add(withName(item, placeholder));
                replaced++;
            }
            if (replaced > 0) {
                classifiers = result;
                record("blank_classifier_name_replaced", null,
                        "{n} clase(s) no tenían nombre; se les puso un nombre provisional «SinNombre-N».", replaced,
                        true);
            }
        }

        void hoistNamespaces() {
            Map<UUID, ModelResource.ClassifierResource> byId = new HashMap<>();
            classifiers.forEach(item -> {
                if (item.id() != null) {
                    byId.putIfAbsent(item.id(), item);
                }
            });
            int hoisted = 0;
            List<ModelResource.ClassifierResource> result = new ArrayList<>();
            for (ModelResource.ClassifierResource item : classifiers) {
                UUID namespace = item.namespaceId();
                if (namespace == null) {
                    result.add(item);
                    continue;
                }
                UUID target = nearestPackage(namespace, byId);
                if (Objects.equals(target, namespace)) {
                    result.add(item);
                    continue;
                }
                ModelResource.ClassifierResource moved = withNamespace(item, target);
                result.add(moved);
                if (item.id() != null && byId.get(item.id()) == item) {
                    byId.put(item.id(), moved);
                }
                hoisted++;
            }
            // A package chain that loops back on itself has no root: cut it at the classifier that closes it.
            for (int index = 0; index < result.size(); index++) {
                ModelResource.ClassifierResource item = result.get(index);
                if (item.id() != null && closesCycle(item, byId)) {
                    ModelResource.ClassifierResource moved = withNamespace(item, null);
                    result.set(index, moved);
                    byId.put(item.id(), moved);
                    hoisted++;
                }
            }
            if (hoisted > 0) {
                classifiers = result;
                record("namespace_hoisted_to_package", null,
                        "{n} clase(s) estaban dentro de algo que no es un paquete; se subieron al paquete más "
                                + "cercano (o a la raíz).", hoisted, true);
            }
        }

        private UUID nearestPackage(UUID start, Map<UUID, ModelResource.ClassifierResource> byId) {
            Set<UUID> visited = new HashSet<>();
            UUID current = start;
            while (current != null && visited.add(current)) {
                ModelResource.ClassifierResource candidate = byId.get(current);
                if (candidate == null) {
                    return null;
                }
                if (candidate.kind() == ClassifierKind.PACKAGE) {
                    return current;
                }
                current = candidate.namespaceId();
            }
            return null;
        }

        private boolean closesCycle(ModelResource.ClassifierResource item,
                Map<UUID, ModelResource.ClassifierResource> byId) {
            Set<UUID> visited = new HashSet<>();
            UUID current = item.namespaceId();
            while (current != null && visited.add(current)) {
                if (current.equals(item.id())) {
                    return true;
                }
                ModelResource.ClassifierResource next = byId.get(current);
                current = next == null ? null : next.namespaceId();
            }
            return false;
        }

        /** Keeps one view (the default, else the first); a file without any view gets the mandatory default view. */
        void reduceToOneView() {
            if (views.size() > 1 || diagrams.size() > 1) {
                ModelResource.ViewResource keep = views.stream().filter(ModelResource.ViewResource::isDefault)
                        .findFirst().orElse(views.isEmpty() ? null : views.get(0));
                UUID keepId = keep == null ? diagrams.get(0).id() : keep.id();
                int extra = Math.max(views.size(), diagrams.size()) - 1;
                List<ModelResource.ViewResource> keptViews = keep == null ? List.of()
                        : List.of(new ModelResource.ViewResource(keep.id(), keep.name(), true,
                                keep.elementGeometry(), keep.connectorPath()));
                XmiReadingResult.Diagram keptDiagram = diagrams.stream().filter(item -> item.id().equals(keepId))
                        .findFirst().orElse(diagrams.isEmpty() ? null : diagrams.get(0));
                views = new ArrayList<>(keptViews);
                diagrams = keptDiagram == null ? new ArrayList<>() : new ArrayList<>(List.of(keptDiagram));
                record("extra_diagrams_not_imported", keep == null ? null : keep.name(),
                        "El archivo traía {n} diagrama(s) más; solo se importó el diagrama por defecto.", extra, true);
            }
            if (views.isEmpty()) {
                synthesizeView();
            } else if (diagrams.isEmpty()) {
                ModelResource.ViewResource view = views.get(0);
                diagrams.add(new XmiReadingResult.Diagram(view.id(), view.name(), true, List.of(), List.of()));
            }
        }

        private void synthesizeView() {
            UUID viewId = diagrams.isEmpty() ? derived("view:" + source.model().id()) : diagrams.get(0).id();
            String name = diagrams.isEmpty() || diagrams.get(0).name() == null || diagrams.get(0).name().isBlank()
                    ? DEFAULT_VIEW_NAME : diagrams.get(0).name();
            List<ModelResource.GeometryResource> geometry = new ArrayList<>();
            int index = 0;
            for (ModelResource.ClassifierResource item : classifiers) {
                if (item.id() == null || item.kind() == ClassifierKind.PACKAGE) {
                    continue;
                }
                int column = index % GRID_COLUMNS;
                int row = index / GRID_COLUMNS;
                geometry.add(new ModelResource.GeometryResource(derived("geometry:" + viewId + ":" + item.id()),
                        TargetType.CLASSIFIER, item.id(), 40 + column * 260, 40 + row * 180, 200, 100, index));
                index++;
            }
            views = new ArrayList<>(List.of(new ModelResource.ViewResource(viewId, name, true, geometry, List.of())));
            if (diagrams.isEmpty()) {
                diagrams = new ArrayList<>(List.of(new XmiReadingResult.Diagram(viewId, name, true, List.of(),
                        List.of())));
            }
            record("diagram_synthesized", name,
                    "El archivo no traía diagrama; se creó la vista por defecto con las clases en cuadrícula.", 1,
                    true);
        }

        /** Drops figures, relationships and traces that point at classifiers or relationships the import lacks. */
        void dropDangling() {
            Set<UUID> classifierIds = new HashSet<>();
            classifiers.forEach(item -> {
                if (item.id() != null) {
                    classifierIds.add(item.id());
                }
            });
            List<ModelResource.RelationshipResource> keptRelationships = new ArrayList<>();
            int droppedMissing = 0;
            int droppedKind = 0;
            for (ModelResource.RelationshipResource relationship : relationships) {
                if (!supportedKind(relationship)) {
                    droppedKind++;
                } else if (relationship.id() == null || relationship.ends().size() != 2 || relationship.ends()
                        .stream().anyMatch(end -> !classifierIds.contains(end.classifierId()))) {
                    droppedMissing++;
                } else {
                    keptRelationships.add(relationship);
                }
            }
            Set<UUID> relationshipIds = new HashSet<>();
            keptRelationships.forEach(item -> relationshipIds.add(item.id()));

            int figures = 0;
            int traces = 0;
            List<ModelResource.ViewResource> newViews = new ArrayList<>();
            for (ModelResource.ViewResource view : views) {
                List<ModelResource.GeometryResource> geometry = view.elementGeometry().stream()
                        .filter(item -> item.targetType() == TargetType.CLASSIFIER
                                && classifierIds.contains(item.targetId())).toList();
                List<ModelResource.ConnectorPathResource> paths = view.connectorPath().stream()
                        .filter(item -> relationshipIds.contains(item.relationshipId())).toList();
                figures += view.elementGeometry().size() - geometry.size();
                traces += view.connectorPath().size() - paths.size();
                newViews.add(geometry.size() == view.elementGeometry().size()
                        && paths.size() == view.connectorPath().size() ? view
                        : new ModelResource.ViewResource(view.id(), view.name(), view.isDefault(), geometry, paths));
            }
            List<XmiReadingResult.Diagram> newDiagrams = new ArrayList<>();
            for (XmiReadingResult.Diagram diagram : diagrams) {
                List<XmiReadingResult.FigurePlacement> placed = diagram.figures().stream()
                        .filter(item -> classifierIds.contains(item.classifierId())).toList();
                List<XmiReadingResult.ConnectorPlacement> connectors = diagram.connectors().stream()
                        .filter(item -> relationshipIds.contains(item.relationshipId())).toList();
                figures += diagram.figures().size() - placed.size();
                traces += diagram.connectors().size() - connectors.size();
                newDiagrams.add(placed.size() == diagram.figures().size()
                        && connectors.size() == diagram.connectors().size() ? diagram
                        : new XmiReadingResult.Diagram(diagram.id(), diagram.name(), diagram.isDefault(), placed,
                                connectors));
            }
            if (droppedMissing > 0) {
                relationships = keptRelationships;
                record("relationships_dropped_missing_classifier", null,
                        "Se descartaron {n} relación(es) con un extremo sin clase importable.", droppedMissing, true);
            }
            if (droppedKind > 0) {
                relationships = keptRelationships;
                record("relationships_dropped_unsupported_kind", null,
                        "Se descartaron {n} relación(es) de un tipo no soportado.", droppedKind, true);
            }
            if (figures > 0) {
                record("figures_dropped_missing_classifier", null,
                        "Se descartaron {n} figura(s) que apuntaban a clases inexistentes o no soportadas.", figures,
                        true);
            }
            if (traces > 0) {
                record("connector_paths_dropped_missing_relationship", null,
                        "Se descartaron {n} trazo(s) de relaciones que ya no existen.", traces, true);
            }
            views = newViews;
            diagrams = newDiagrams;
        }

        private boolean supportedKind(ModelResource.RelationshipResource relationship) {
            try {
                RelationshipKind.valueOf(relationship.kind().toUpperCase(Locale.ROOT));
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        }

        /** Clamps non-positive sizes to the minimum, drops repeated placements of a classifier and fills ids. */
        void normalizeGeometry() {
            int clamped = 0;
            int duplicates = 0;
            List<ModelResource.ViewResource> newViews = new ArrayList<>();
            for (ModelResource.ViewResource view : views) {
                Set<UUID> placed = new HashSet<>();
                List<ModelResource.GeometryResource> geometry = new ArrayList<>();
                boolean changed = false;
                for (ModelResource.GeometryResource item : view.elementGeometry()) {
                    if (!placed.add(item.targetId())) {
                        duplicates++;
                        changed = true;
                        continue;
                    }
                    int width = item.w() <= 0 ? MIN_WIDTH : item.w();
                    int height = item.h() <= 0 ? MIN_HEIGHT : item.h();
                    UUID id = item.id() == null ? derived("geometry:" + view.id() + ":" + item.targetId())
                            : item.id();
                    if (width != item.w() || height != item.h()) {
                        clamped++;
                    }
                    if (width != item.w() || height != item.h() || id != item.id()) {
                        changed = true;
                        item = new ModelResource.GeometryResource(id, item.targetType(), item.targetId(), item.x(),
                                item.y(), width, height, item.zOrder());
                    }
                    geometry.add(item);
                }
                newViews.add(changed ? new ModelResource.ViewResource(view.id(), view.name(), view.isDefault(),
                        geometry, view.connectorPath()) : view);
            }
            views = newViews;
            if (clamped > 0) {
                record("geometry_clamped", null,
                        "Se ajustaron {n} figura(s) con tamaño no válido al tamaño mínimo.", clamped, true);
            }
            if (duplicates > 0) {
                record("duplicate_geometry_dropped", null,
                        "Se descartaron {n} figura(s) repetidas de una misma clase.", duplicates, true);
            }
        }

        private static ModelResource.ClassifierResource withName(ModelResource.ClassifierResource item, String name) {
            return new ModelResource.ClassifierResource(item.id(), name, item.kind(), item.isAbstract(),
                    item.namespaceId(), item.attributes(), item.operations(), item.constraints(),
                    item.storeOverride(), item.store(), item.storeReason());
        }

        private static ModelResource.ClassifierResource withNamespace(ModelResource.ClassifierResource item,
                UUID namespace) {
            return new ModelResource.ClassifierResource(item.id(), item.name(), item.kind(), item.isAbstract(),
                    namespace, item.attributes(), item.operations(), item.constraints(), item.storeOverride(),
                    item.store(), item.storeReason());
        }

        static ModelResource.ClassifierResource renamed(ModelResource.ClassifierResource item, String name) {
            return withName(item, name);
        }

        private static UUID derived(String key) {
            return UUID.nameUUIDFromBytes(("xmi-repair:" + key).getBytes(StandardCharsets.UTF_8));
        }
    }
}
