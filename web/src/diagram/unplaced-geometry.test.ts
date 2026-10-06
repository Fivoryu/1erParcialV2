import { describe, expect, it } from 'vitest';
import type { ClassifierResource, ModelResource } from '../api/types';
import { buildUnplacedGeometryRequests } from './unplaced-geometry';

const placed: ClassifierResource = {
  id: '11111111-1111-4111-8111-111111111111',
  name: 'Account',
  kind: 'CLASS',
  isAbstract: false,
  namespaceId: null,
  attributes: [],
  operations: [],
  constraints: [],
};

const unplaced: ClassifierResource = {
  ...placed,
  id: '22222222-2222-4222-8222-222222222222',
  name: 'Fresh',
};

const unplacedToo: ClassifierResource = {
  ...placed,
  id: '33333333-3333-4333-8333-333333333333',
  name: 'Later',
};

const model: ModelResource = {
  id: '44444444-4444-4444-8444-444444444444',
  projectId: '55555555-5555-4555-8555-555555555555',
  revision: 3,
  versionId: '66666666-6666-4666-8666-666666666666',
  views: [{
    id: '77777777-7777-4777-8777-777777777777',
    name: 'Class diagram',
    is_default: true,
    element_geometry: [{
      id: '88888888-8888-4888-8888-888888888888',
      targetType: 'CLASSIFIER',
      targetId: placed.id,
      x: 120,
      y: 80,
      w: 260,
      h: 180,
      zOrder: 1,
    }],
    connector_path: [],
  }],
  classifiers: [placed, unplaced],
  relationships: [],
};

function node(classifier: ClassifierResource, position: { x: number; y: number }) {
  return {
    id: classifier.id,
    position,
    width: 260,
    height: 180,
    zIndex: 0,
    data: { classifier },
  };
}

/** A copy of the model where the given classifier has saved geometry in the default view. */
function modelWithGeometryFor(classifier: ClassifierResource): ModelResource {
  const [view] = model.views;
  return {
    ...model,
    classifiers: [classifier],
    views: [{
      ...view!,
      element_geometry: [{
        id: '88888888-8888-4888-8888-888888888888',
        targetType: 'CLASSIFIER',
        targetId: classifier.id,
        x: 120,
        y: 80,
        w: 260,
        h: 180,
        zOrder: 1,
      }],
    }],
  };
}

describe('buildUnplacedGeometryRequests', () => {
  it('yields exactly one request with the rendered rectangle for a classifier without saved geometry', () => {
    const decision = buildUnplacedGeometryRequests(
      model,
      [node(placed, { x: 120, y: 80 }), node(unplaced, { x: 56, y: 56 })],
      new Map(),
    );

    expect(decision.requests).toEqual([{
      targetType: 'CLASSIFIER',
      viewId: '77777777-7777-4777-8777-777777777777',
      targetId: unplaced.id,
      x: 56,
      y: 56,
      w: 260,
      h: 180,
      zOrder: 0,
    }]);
  });

  it('prefers the measured DOM rectangle over the declared dimensions', () => {
    const measured = {
      ...node(unplaced, { x: 56, y: 56 }),
      measured: { width: 248.4, height: 171.2 },
    };

    expect(buildUnplacedGeometryRequests(model, [measured], new Map()).requests).toEqual([{
      targetType: 'CLASSIFIER',
      viewId: '77777777-7777-4777-8777-777777777777',
      targetId: unplaced.id,
      x: 56,
      y: 56,
      w: 248,
      h: 171,
      zOrder: 0,
    }]);
  });

  it('yields none for a classifier that already has saved geometry in the default view', () => {
    const decision = buildUnplacedGeometryRequests(
      model,
      [node(placed, { x: 120, y: 80 })],
      new Map(),
    );

    expect(decision.requests).toEqual([]);
  });

  it('yields two requests in model order for two unplaced classifiers', () => {
    const mixed = {
      ...model,
      classifiers: [unplaced, placed, unplacedToo],
    };

    const decision = buildUnplacedGeometryRequests(
      mixed,
      [node(unplaced, { x: 56, y: 56 }), node(unplacedToo, { x: 396, y: 56 })],
      new Map(),
    );

    expect(decision.requests.map((request) => request.targetId)).toEqual([unplaced.id, unplacedToo.id]);
  });

  it('clamps degenerate sizes to at least one pixel like the drag path', () => {
    const degenerate = {
      ...node(unplaced, { x: 10.6, y: -2.4 }),
      width: 0,
      height: 0,
      zIndex: 3.7,
    };

    expect(buildUnplacedGeometryRequests(model, [degenerate], new Map()).requests).toEqual([{
      targetType: 'CLASSIFIER',
      viewId: '77777777-7777-4777-8777-777777777777',
      targetId: unplaced.id,
      x: 11,
      y: -2,
      w: 1,
      h: 1,
      zOrder: 4,
    }]);
  });

  it('yields an empty array when there is no default view to persist into', () => {
    const viewless = { ...model, views: [] };

    expect(buildUnplacedGeometryRequests(viewless, [node(unplaced, { x: 56, y: 56 })], new Map()).requests).toEqual([]);
  });

  it('yields nothing and keeps the bookkeeping unchanged when every classifier is placed', () => {
    const attempts = new Map([[unplaced.id, { count: 1, revision: model.revision }]]);
    const decision = buildUnplacedGeometryRequests(model, [], attempts);

    expect(decision.requests).toEqual([]);
    expect(decision.attempts.get(unplaced.id)).toEqual({ count: 1, revision: model.revision });
  });
});

describe('buildUnplacedGeometryRequests retry policy', () => {
  it('records the first attempt in the returned bookkeeping', () => {
    const decision = buildUnplacedGeometryRequests(model, [node(unplaced, { x: 56, y: 56 })], new Map());

    expect(decision.requests).toHaveLength(1);
    expect(decision.attempts.get(unplaced.id)).toEqual({ count: 1, revision: model.revision });
  });

  it('requests a classifier only once per model revision even when node churn re-runs the effect', () => {
    const first = buildUnplacedGeometryRequests(model, [node(unplaced, { x: 56, y: 56 })], new Map());
    expect(first.requests).toHaveLength(1);

    // React Flow re-measures and replaces node objects a frame or two after mount,
    // before any server round trip: the effect runs again with the SAME model revision
    // and must not send the identical geometry.set command a second time.
    const remeasured = {
      ...node(unplaced, { x: 56, y: 56 }),
      measured: { width: 250, height: 170 },
    };
    const second = buildUnplacedGeometryRequests(model, [remeasured], first.attempts);

    expect(second.requests).toEqual([]);
    expect(second.attempts.get(unplaced.id)).toEqual({ count: 1, revision: model.revision });
  });

  it('sends exactly one retry per new model revision no matter how often the effect re-runs', () => {
    const first = buildUnplacedGeometryRequests(model, [node(unplaced, { x: 56, y: 56 })], new Map());
    const refetched = { ...model, revision: model.revision + 1 };

    const retry = buildUnplacedGeometryRequests(refetched, [node(unplaced, { x: 56, y: 56 })], first.attempts);
    expect(retry.requests).toHaveLength(1);
    expect(retry.attempts.get(unplaced.id)).toEqual({ count: 2, revision: refetched.revision });

    // A second run on the SAME revision (node churn again) must not duplicate the retry.
    const rerun = buildUnplacedGeometryRequests(refetched, [node(unplaced, { x: 40, y: 40 })], retry.attempts);
    expect(rerun.requests).toEqual([]);
    expect(rerun.attempts.get(unplaced.id)).toEqual({ count: 2, revision: refetched.revision });
  });

  it('retries a classifier whose refetched model still shows no saved geometry', () => {
    const first = buildUnplacedGeometryRequests(model, [node(unplaced, { x: 56, y: 56 })], new Map());

    // Rejection signal: the model came back refetched (new revision) and the classifier
    // still has no saved geometry in the default view.
    const refetched = { ...model, revision: model.revision + 1 };
    const second = buildUnplacedGeometryRequests(refetched, [node(unplaced, { x: 56, y: 56 })], first.attempts);

    expect(second.requests).toHaveLength(1);
    expect(second.requests[0]!.targetId).toBe(unplaced.id);
    expect(second.attempts.get(unplaced.id)).toEqual({ count: 2, revision: refetched.revision });
  });

  it('stops requesting once the attempt limit is exhausted', () => {
    const exhausted = new Map([[unplaced.id, { count: 3, revision: model.revision }]]);

    const decision = buildUnplacedGeometryRequests(model, [node(unplaced, { x: 56, y: 56 })], exhausted);

    expect(decision.requests).toEqual([]);
    expect(decision.attempts.get(unplaced.id)).toEqual({ count: 3, revision: model.revision });
  });

  it('honours a smaller explicit limit across revisions before giving up', () => {
    const first = buildUnplacedGeometryRequests(model, [node(unplaced, { x: 56, y: 56 })], new Map(), 2);
    expect(first.requests).toHaveLength(1);

    const refetched = { ...model, revision: model.revision + 1 };
    const second = buildUnplacedGeometryRequests(refetched, [node(unplaced, { x: 56, y: 56 })], first.attempts, 2);
    expect(second.requests).toHaveLength(1);

    const exhausted = { ...model, revision: model.revision + 2 };
    const third = buildUnplacedGeometryRequests(exhausted, [node(unplaced, { x: 56, y: 56 })], second.attempts, 2);
    expect(third.requests).toEqual([]);
  });

  it('releases the bookkeeping when the model gains geometry and does not re-request it', () => {
    const gained = modelWithGeometryFor(unplaced);

    const decision = buildUnplacedGeometryRequests(
      gained,
      [node(unplaced, { x: 120, y: 80 })],
      new Map([[unplaced.id, { count: 1, revision: model.revision }]]),
    );

    expect(decision.requests).toEqual([]);
    expect(decision.attempts.has(unplaced.id)).toBe(false);
  });

  it('requests a classifier again after it gained and later lost geometry', () => {
    const gained = modelWithGeometryFor(unplaced);
    const released = buildUnplacedGeometryRequests(
      gained,
      [node(unplaced, { x: 120, y: 80 })],
      new Map([[unplaced.id, { count: 1, revision: model.revision }]]),
    );
    expect(released.attempts.has(unplaced.id)).toBe(false);

    // The geometry is gone again (removed and re-arrived without saved geometry).
    const lost = buildUnplacedGeometryRequests(model, [node(unplaced, { x: 56, y: 56 })], released.attempts);

    expect(lost.requests).toHaveLength(1);
    expect(lost.requests[0]!.targetId).toBe(unplaced.id);
    expect(lost.attempts.get(unplaced.id)).toEqual({ count: 1, revision: model.revision });
  });

  it('requests nothing when nothing is unplaced', () => {
    const decision = buildUnplacedGeometryRequests(
      modelWithGeometryFor(placed),
      [node(placed, { x: 120, y: 80 })],
      new Map(),
    );

    expect(decision.requests).toEqual([]);
  });

  it('does not count an attempt for a node without usable dimensions', () => {
    const decision = buildUnplacedGeometryRequests(model, [], new Map());

    expect(decision.requests).toEqual([]);
    expect(decision.attempts.has(unplaced.id)).toBe(false);
  });
});
