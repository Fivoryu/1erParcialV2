package com.diagramador.xmi;

import com.diagramador.api.ModelResource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies the closed choices a user (or the assistant) made for blockers: rename a classifier or the diagram, skip
 * a classifier, or pick the diagram to keep. A resolution that names nothing in the file, or a rename without a
 * name, is ignored; applied ones are returned as repairs, and what a skip removes is counted as a loss.
 */
public class XmiResolutionApplier {

    public XmiRepair.Result apply(XmiReadingResult reading, List<XmiResolution> resolutions) {
        if (resolutions == null || resolutions.isEmpty()) {
            return new XmiRepair.Result(reading, List.of());
        }
        XmiRepair.Draft draft = new XmiRepair.Draft(reading);
        resolutions.stream().filter(item -> item != null && item.action() == XmiResolution.Action.KEEP_VIEW)
                .forEach(item -> keepView(draft, item));
        resolutions.stream().filter(item -> item != null && item.action() == XmiResolution.Action.RENAME)
                .forEach(item -> rename(draft, item));
        boolean skipped = false;
        for (XmiResolution item : resolutions) {
            if (item != null && item.action() == XmiResolution.Action.SKIP) {
                skipped |= skip(draft, item);
            }
        }
        if (skipped) {
            draft.dropDangling();
        }
        return draft.finish();
    }

    private void keepView(XmiRepair.Draft draft, XmiResolution resolution) {
        if (draft.views().size() < 2 || draft.views().stream().noneMatch(view -> matches(view.id(), resolution))) {
            return;
        }
        List<ModelResource.ViewResource> views = new ArrayList<>();
        for (ModelResource.ViewResource view : draft.views()) {
            views.add(new ModelResource.ViewResource(view.id(), view.name(), matches(view.id(), resolution),
                    view.elementGeometry(), view.connectorPath()));
        }
        List<XmiReadingResult.Diagram> diagrams = new ArrayList<>();
        for (XmiReadingResult.Diagram diagram : draft.diagrams()) {
            diagrams.add(new XmiReadingResult.Diagram(diagram.id(), diagram.name(), matches(diagram.id(), resolution),
                    diagram.figures(), diagram.connectors()));
        }
        draft.setViews(views, diagrams);
        draft.record("resolution_keep_view", resolution.subject(),
                "Se eligió el diagrama que se conserva al importar.", 1, false);
    }

    private void rename(XmiRepair.Draft draft, XmiResolution resolution) {
        if (resolution.newName() == null || resolution.newName().isBlank()) {
            return;
        }
        String name = resolution.newName().strip();
        List<ModelResource.ClassifierResource> classifiers = new ArrayList<>();
        boolean renamed = false;
        for (ModelResource.ClassifierResource item : draft.classifiers()) {
            if (!renamed && matches(item.id(), resolution) && !name.equals(item.name())) {
                classifiers.add(XmiRepair.Draft.renamed(item, name));
                renamed = true;
                draft.record("resolution_renamed", resolution.subject(),
                        "Se renombraron {n} elemento(s) para evitar un nombre repetido.", 1, false);
            } else {
                classifiers.add(item);
            }
        }
        draft.setClassifiers(classifiers);
        if (renamed) {
            return;
        }
        List<ModelResource.ViewResource> views = new ArrayList<>();
        List<XmiReadingResult.Diagram> diagrams = new ArrayList<>();
        boolean viewRenamed = false;
        for (ModelResource.ViewResource view : draft.views()) {
            if (matches(view.id(), resolution) && !name.equals(view.name())) {
                views.add(new ModelResource.ViewResource(view.id(), name, view.isDefault(), view.elementGeometry(),
                        view.connectorPath()));
                viewRenamed = true;
            } else {
                views.add(view);
            }
        }
        for (XmiReadingResult.Diagram diagram : draft.diagrams()) {
            diagrams.add(viewRenamed && matches(diagram.id(), resolution)
                    ? new XmiReadingResult.Diagram(diagram.id(), name, diagram.isDefault(), diagram.figures(),
                            diagram.connectors()) : diagram);
        }
        if (viewRenamed) {
            draft.setViews(views, diagrams);
            draft.record("resolution_renamed", resolution.subject(),
                    "Se renombraron {n} elemento(s) para evitar un nombre repetido.", 1, false);
        }
    }

    private boolean skip(XmiRepair.Draft draft, XmiResolution resolution) {
        List<ModelResource.ClassifierResource> kept = draft.classifiers().stream()
                .filter(item -> !matches(item.id(), resolution)).toList();
        int removed = draft.classifiers().size() - kept.size();
        if (removed == 0) {
            return false;
        }
        draft.setClassifiers(kept);
        draft.record("classifiers_skipped_by_resolution", resolution.subject(),
                "Se omitieron {n} clase(s) del archivo por elección del usuario.", removed, true);
        return true;
    }

    private static boolean matches(java.util.UUID id, XmiResolution resolution) {
        return id != null && resolution.subject() != null && Objects.equals(id.toString(),
                resolution.subject().strip().toLowerCase(java.util.Locale.ROOT));
    }
}
