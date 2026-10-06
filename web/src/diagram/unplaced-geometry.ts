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

/**
 * Attempt bookkeeping for one classifier: `count` is how many geometry requests were
 * sent for it in this mount, `revision` is the `model.revision` the last request was
 * made for. Bounded retry policy (unit decision SPP-04): the cadence is at most ONE
 * request per classifier per model revision — an entry stamped with the current model
 * revision blocks any further request for that classifier until a NEW revision arrives,
 * because the effect re-runs on node identity churn (React Flow re-measures and replaces
 * node objects a frame or two after mount) before any outcome is known, and duplicate
 * runs on the same revision would send identical commands. A retry happens only when a
 * new model revision still shows no saved geometry for the classifier (the "command was
 * not accepted" signal — there is no synchronous acknowledgement) and only while `count`
 * is below the bound. A classifier that reached the limit is never requested again for
 * the rest of the mount; one that gained geometry or left the model is released so a
 * later removal and re-arrival can request again from a clean count.
 */
export interface UnplacedGeometryAttempt {
  readonly count: number;
  readonly revision: number;
}

/** Per-classifier attempt bookkeeping, keyed by classifier id. */
export type UnplacedGeometryAttempts = ReadonlyMap<string, UnplacedGeometryAttempt>;

/** Default bound for the bounded retry (unit decision SPP-04). */
export const DEFAULT_UNPLACED_GEOMETRY_ATTEMPTS = 3;

/**
 * What the caller must apply after one decision: the geometry requests to send, plus the
 * updated attempt bookkeeping to keep for the next decision. The effect only applies what
 * it receives; every increment, release and exhaustion rule lives here, testable without
 * a DOM (unit decision SPP-03).
 */
export interface UnplacedGeometryDecision {
  requests: GeometryUpdate[];
  attempts: Map<string, UnplacedGeometryAttempt>;
}

function defaultView(model: ModelResource) {
  return model.views.find((view) => view.is_default) ?? model.views[0];
}

function savedGeometry(model: ModelResource, classifierId: string) {
  return defaultView(model)?.element_geometry.find((geometry) =>
    geometry.targetType === 'CLASSIFIER' && geometry.targetId === classifierId);
}

/**
 * Pure retry-aware decision for the unplaced-geometry effect. Signature note: this used
 * to return a bare `GeometryUpdate[]` and take an `alreadyRequested: ReadonlySet<string>`
 * guard; it now takes the attempt bookkeeping (`UnplacedGeometryAttempts`, per unit
 * decision SPP-03) plus an optional bounded limit (default
 * `DEFAULT_UNPLACED_GEOMETRY_ATTEMPTS`, per unit decision SPP-04), and returns an
 * `UnplacedGeometryDecision` with both the requests and the updated bookkeeping.
 *
 * One geometry request per classifier the canvas renders without saved geometry in the
 * default view (fallback grid position included), at most ONE per classifier per model
 * revision, and only while its attempt count is still below `maxAttempts`. A position
 * that exists only in the render is invisible to every other consumer, including the XMI
 * export, so what the user sees must be written back to the model. Rounding and clamping
 * mirror the drag path (`Math.round`, sizes at least 1, rounded `zOrder`). Order is
 * deterministic (model order); nothing to persist yields an empty array. Nodes without
 * usable dimensions are skipped without counting an attempt, because there is no honest
 * rectangle to persist for them yet. A classifier already requested for the current
 * `model.revision` is skipped whatever the nodes did, so node churn (React Flow
 * re-measurement) never duplicates the command. A retry happens only when a NEW model
 * revision arrives and the classifier still has no saved geometry. Bookkeeping entries
 * for classifiers that left the model or gained saved geometry are released, so a later
 * removal and re-arrival can request again.
 */
export function buildUnplacedGeometryRequests(
  model: ModelResource,
  nodes: readonly UnplacedNodeSource[],
  attempts: UnplacedGeometryAttempts = new Map(),
  maxAttempts: number = DEFAULT_UNPLACED_GEOMETRY_ATTEMPTS,
): UnplacedGeometryDecision {
  const view = defaultView(model);
  if (!view) {
    return { requests: [], attempts: new Map(attempts) };
  }
  const nodesById = new Map(nodes.map((node) => [node.id, node]));
  const modelIds = new Set(model.classifiers.map((classifier) => classifier.id));
  // Rebuild the bookkeeping from the current model: classifiers that gained geometry or
  // left the model are released, so their next arrival starts from a clean count.
  const nextAttempts = new Map<string, UnplacedGeometryAttempt>();
  for (const [classifierId, attempt] of attempts) {
    if (modelIds.has(classifierId) && !savedGeometry(model, classifierId)) {
      nextAttempts.set(classifierId, attempt);
    }
  }
  const requests: GeometryUpdate[] = [];
  for (const classifier of model.classifiers) {
    const attempt = nextAttempts.get(classifier.id);
    if (savedGeometry(model, classifier.id) || (attempt?.count ?? 0) >= maxAttempts) {
      continue;
    }
    // One request per classifier per model revision: the effect re-runs on node churn
    // (React Flow re-measurement) before any outcome is known, so an entry stamped with
    // the current revision suppresses duplicate identical commands until a NEW model
    // revision arrives and the classifier still has no saved geometry.
    if (attempt && attempt.revision === model.revision) {
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
    nextAttempts.set(classifier.id, {
      count: (attempt?.count ?? 0) + 1,
      revision: model.revision,
    });
  }
  return { requests, attempts: nextAttempts };
}
