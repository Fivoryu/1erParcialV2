import type { ClassifierResource, ModelResource } from '../api/types';
import type { GeometryUpdate } from './commands';

/**
 * Minimal structural view of a React Flow node carrying a classifier, so this helper
 * stays testable without the canvas component. `measured` holds the DOM size reported
 * by React Flow v12; it wins over the declared `width`/`height` exactly like the drag
 * path's rectangle does.
 */
export interface UnplacedNodeSource {
  id: string;
  position: { x: number; y: number };
  width?: number;
  height?: number;
  measured?: { width?: number; height?: number } | undefined;
  zIndex?: number | undefined;
  data: { classifier: ClassifierResource };
}

function defaultView(model: ModelResource) {
  return model.views.find((view) => view.is_default) ?? model.views[0];
}

function savedGeometry(model: ModelResource, classifierId: string) {
  return defaultView(model)?.element_geometry.find((geometry) =>
    geometry.targetType === 'CLASSIFIER' && geometry.targetId === classifierId);
}

/**
 * One geometry request per classifier the canvas renders without saved geometry in the
 * default view (fallback grid position included) and not present in `alreadyRequested`.
 * A position that exists only in the render is invisible to every other consumer,
 * including the XMI export, so what the user sees must be written back to the model.
 * Rounding and clamping mirror the drag path (`Math.round`, sizes at least 1, rounded
 * `zOrder`). Order is deterministic (model order); nothing to persist yields an empty
 * array. Nodes without usable dimensions are skipped, because there is no honest
 * rectangle to persist for them.
 */
export function buildUnplacedGeometryRequests(
  model: ModelResource,
  nodes: readonly UnplacedNodeSource[],
  alreadyRequested: ReadonlySet<string>,
): GeometryUpdate[] {
  const view = defaultView(model);
  if (!view) {
    return [];
  }
  const nodesById = new Map(nodes.map((node) => [node.id, node]));
  const requests: GeometryUpdate[] = [];
  for (const classifier of model.classifiers) {
    if (savedGeometry(model, classifier.id) || alreadyRequested.has(classifier.id)) {
      continue;
    }
    const node = nodesById.get(classifier.id);
    const width = node?.measured?.width ?? node?.width;
    const height = node?.measured?.height ?? node?.height;
    if (!node || width === undefined || height === undefined) {
      continue;
    }
    requests.push({
      targetType: 'CLASSIFIER',
      viewId: view.id,
      targetId: classifier.id,
      x: Math.round(node.position.x),
      y: Math.round(node.position.y),
      w: Math.max(1, Math.round(width)),
      h: Math.max(1, Math.round(height)),
      zOrder: Math.round(node.zIndex ?? 0),
    });
  }
  return requests;
}
