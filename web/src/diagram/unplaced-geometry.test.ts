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

describe('buildUnplacedGeometryRequests', () => {
  it('yields exactly one request with the rendered rectangle for a classifier without saved geometry', () => {
    const requests = buildUnplacedGeometryRequests(model, [node(placed, { x: 120, y: 80 }), node(unplaced, { x: 56, y: 56 })], new Set());

    expect(requests).toEqual([{
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

    expect(buildUnplacedGeometryRequests(model, [measured], new Set())).toEqual([{
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

  it('yields none for a classifier already requested', () => {
    const requests = buildUnplacedGeometryRequests(
      model,
      [node(placed, { x: 120, y: 80 }), node(unplaced, { x: 56, y: 56 })],
      new Set([unplaced.id]),
    );

    expect(requests).toEqual([]);
  });

  it('yields none for a classifier that already has saved geometry in the default view', () => {
    const requests = buildUnplacedGeometryRequests(model, [node(placed, { x: 120, y: 80 })], new Set());

    expect(requests).toEqual([]);
  });

  it('yields two requests in model order for two unplaced classifiers', () => {
    const mixed = {
      ...model,
      classifiers: [unplaced, placed, unplacedToo],
    };

    const requests = buildUnplacedGeometryRequests(
      mixed,
      [node(unplaced, { x: 56, y: 56 }), node(unplacedToo, { x: 396, y: 56 })],
      new Set(),
    );

    expect(requests.map((request) => request.targetId)).toEqual([unplaced.id, unplacedToo.id]);
  });

  it('clamps degenerate sizes to at least one pixel like the drag path', () => {
    const degenerate = {
      ...node(unplaced, { x: 10.6, y: -2.4 }),
      width: 0,
      height: 0,
      zIndex: 3.7,
    };

    expect(buildUnplacedGeometryRequests(model, [degenerate], new Set())).toEqual([{
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

    expect(buildUnplacedGeometryRequests(viewless, [node(unplaced, { x: 56, y: 56 })], new Set())).toEqual([]);
  });

  it('yields an empty array when every classifier is placed or already requested', () => {
    expect(buildUnplacedGeometryRequests(model, [], new Set())).toEqual([]);
  });
});
