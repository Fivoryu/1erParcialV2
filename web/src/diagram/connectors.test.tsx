import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ModelResource, RelationshipEndResource, RelationshipResource } from '../api/types';
import {
  buildGeometrySetRequest,
  buildRelationshipDeleteRequest,
  buildRelationshipUpdateRequest,
  RELATIONSHIP_KINDS,
  type GeometryUpdate,
  type ModelCommandRequest,
} from './commands';
import { createGeometryCoalescer } from './geometry-coalescer';
import { buildFlowEdges, buildFlowNodes } from './UmlCanvas';
import { RelationshipEndLabels, relationshipMarkerPresentation } from './UmlRelationshipEdge';
import PropertiesPanel from './PropertiesPanel';

const sourceId = '11111111-1111-4111-8111-111111111111';
const targetId = '22222222-2222-4222-8222-222222222222';
const relationshipId = '33333333-3333-4333-8333-333333333333';
const viewId = '44444444-4444-4444-8444-444444444444';

const sourceEnd: RelationshipEndResource = {
  id: '55555555-5555-4555-8555-555555555555',
  classifierId: sourceId,
  role: 'customer',
  multiplicity: 'ZERO_OR_MANY',
  navigability: 'NAVIGABLE',
  aggregationKind: 'NONE',
  ordinal: 1,
};

const targetEnd: RelationshipEndResource = {
  id: '66666666-6666-4666-8666-666666666666',
  classifierId: targetId,
  role: 'subscriptions',
  multiplicity: 'ONE_OR_MANY',
  navigability: 'NOT_NAVIGABLE',
  aggregationKind: 'NONE',
  ordinal: 2,
};

const relationship: RelationshipResource = {
  id: relationshipId,
  kind: 'ASSOCIATION',
  name: 'owns',
  ends: [sourceEnd, targetEnd],
};

const model: ModelResource = {
  id: '77777777-7777-4777-8777-777777777777',
  projectId: '88888888-8888-4888-8888-888888888888',
  revision: 5,
  views: [{
    id: viewId,
    name: 'Class diagram',
    is_default: true,
    element_geometry: [
      { id: '99999999-9999-4999-8999-999999999999', targetType: 'CLASSIFIER', targetId: sourceId, x: 96, y: 72, w: 240, h: 160, zOrder: 1 },
      { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', targetType: 'CLASSIFIER', targetId: targetId, x: 456, y: 72, w: 260, h: 180, zOrder: 2 },
    ],
    connector_path: [
      { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', relationshipId, ordinal: 0, x: 360, y: 144 },
      { id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', relationshipId, ordinal: 1, x: 420, y: 144 },
    ],
  }],
  classifiers: [
    { id: sourceId, name: 'Customer', kind: 'CLASS', isAbstract: false, namespaceId: null, attributes: [], operations: [], constraints: [] },
    { id: targetId, name: 'Subscription', kind: 'CLASS', isAbstract: false, namespaceId: null, attributes: [], operations: [], constraints: [] },
  ],
  relationships: [relationship],
};

const markerEnds = [
  { ...sourceEnd, aggregationKind: 'SHARED' },
  targetEnd,
].reverse();

const markerCases = [
  ['ASSOCIATION', 'solid', 'open-arrow', null, false],
  ['AGGREGATION', 'solid', 'hollow-diamond', null, false],
  ['COMPOSITION', 'solid', 'filled-diamond', null, false],
  ['GENERALIZATION', 'solid', null, 'hollow-triangle', false],
  ['INTERFACE_REALIZATION', 'dashed', null, 'hollow-triangle', true],
  ['DEPENDENCY', 'dashed', null, 'open-arrow', false],
] as const;

afterEach(() => {
  vi.useRealTimers();
});

describe('UML relationship markers and labels', () => {
  it.each(markerCases)(
    '%s uses source marker %s and target marker %s',
    (kind, lineStyle, sourceMarker, targetMarker, realizationLabelAtTarget) => {
      const presentation = relationshipMarkerPresentation(kind, markerEnds);
      expect(presentation).toEqual({ lineStyle, sourceMarker, targetMarker, realizationLabelAtTarget });
      const diamonds = [presentation.sourceMarker, presentation.targetMarker]
        .filter((marker) => marker?.includes('diamond'));
      if (kind === 'AGGREGATION' || kind === 'COMPOSITION') {
        expect(diamonds).toEqual([sourceMarker]);
        expect(presentation.sourceMarker).toBe(kind === 'AGGREGATION' ? 'hollow-diamond' : 'filled-diamond');
      } else {
        expect(diamonds).toEqual([]);
      }
    },
  );

  it('renders both end roles and multiplicities in UML notation without exposing identifiers or enums', () => {
    const markup = renderToStaticMarkup(createElement(RelationshipEndLabels, {
      sourceEnd,
      targetEnd,
      realizationLabelAtTarget: true,
    }));

    expect(markup).toContain('customer [0..*]');
    expect(markup).toContain('subscriptions [1..*]');
    expect(markup).toContain('«realize»');
    expect(markup).not.toContain(sourceId);
    expect(markup).not.toContain(targetId);
    for (const technicalValue of ['ZERO_OR_MANY', 'ONE_OR_MANY', 'NAVIGABLE', 'NOT_NAVIGABLE', 'aggregation_kind']) {
      expect(markup).not.toContain(technicalValue);
    }
  });

  it('offers only supported relationship properties and sends the cascade confirmation for deletion', () => {
    const panel = renderToStaticMarkup(createElement(PropertiesPanel, {
      model,
      selection: { kind: 'relationship', relationshipId },
      onCommand: () => null,
      onDelete: () => undefined,
      onSelect: () => undefined,
    }));

    expect(panel).toContain('Relationship');
    expect(panel).toContain('Source end · Customer');
    expect(panel).toContain('Target end · Subscription');
    expect(panel).toContain('Role');
    expect(panel).toContain('Multiplicity');
    expect(panel).toContain('Navigability');
    expect(panel).toContain('Interface realization');
    expect(panel).toContain('1');
    expect(panel).toContain('0..1');
    expect(panel).toContain('1..*');
    expect(panel).toContain('0..*');
    expect(panel).not.toContain('RANGE');
    expect(RELATIONSHIP_KINDS).toEqual([
      'ASSOCIATION',
      'AGGREGATION',
      'COMPOSITION',
      'GENERALIZATION',
      'INTERFACE_REALIZATION',
      'DEPENDENCY',
    ]);

    expect(buildRelationshipDeleteRequest(relationship)).toEqual({
      type: 'relationship.delete',
      targets: [{ scope: 'relationship', id: relationshipId }],
      preconditions: [{ kind: 'cascade_count', expect: 3, scope: null }],
      payload: {},
    });
  });

  it('builds relationship updates only from the six supported kinds and editable end properties', () => {
    const update = buildRelationshipUpdateRequest(relationship, {
      name: 'subscribes',
      kind: 'COMPOSITION',
      source: { role: 'owner', multiplicity: 'EXACTLY_ONE', navigability: 'NAVIGABLE' },
      target: { role: 'plans', multiplicity: 'ZERO_OR_MANY', navigability: 'NOT_NAVIGABLE' },
    });

    expect(update).toEqual({
      type: 'relationship.update',
      targets: [{ scope: 'relationship', id: relationshipId }],
      payload: {
        name: 'subscribes',
        kind: 'COMPOSITION',
        source: { role: 'owner', multiplicity: 'EXACTLY_ONE', navigability: 'NAVIGABLE' },
        target: { role: 'plans', multiplicity: 'ZERO_OR_MANY', navigability: 'NOT_NAVIGABLE' },
      },
    });
    expect(update.payload).not.toHaveProperty('aggregationKind');
  });
});

describe('coalesced persisted geometry', () => {
  it('emits exactly one command for a drag of N updates and one more after a second settle', () => {
    vi.useFakeTimers();
    const commands: ModelCommandRequest[] = [];
    const coalescer = createGeometryCoalescer<GeometryUpdate>(
      (_elementId, payload) => commands.push(buildGeometrySetRequest(payload)),
      240,
    );
    const payload = (x: number): GeometryUpdate => ({
      targetType: 'CLASSIFIER', viewId, targetId: sourceId, x, y: 72, w: 240, h: 160, zOrder: 1,
    });

    for (let update = 1; update <= 100; update += 1) {
      coalescer.schedule(`classifier:${sourceId}`, payload(update));
    }
    expect(commands).toHaveLength(0);
    coalescer.flush(`classifier:${sourceId}`);
    expect(commands).toHaveLength(1);
    expect(commands[0]?.payload?.x).toBe(100);

    for (let update = 101; update <= 125; update += 1) {
      coalescer.schedule(`classifier:${sourceId}`, payload(update));
    }
    vi.advanceTimersByTime(240);
    expect(commands).toHaveLength(2);
    expect(commands[1]?.payload?.x).toBe(125);
  });

  it('reads saved positions and waypoints and sends matching geometry on the next settle', () => {
    const nodes = buildFlowNodes(model, [], () => undefined, () => undefined);
    const edges = buildFlowEdges(model);
    const customer = nodes.find((node) => node.id === sourceId);
    const subscription = nodes.find((node) => node.id === targetId);
    const connector = edges.find((edge) => edge.id === relationshipId);

    expect(customer?.position).toEqual({ x: 96, y: 72 });
    expect(customer?.width).toBe(240);
    expect(customer?.height).toBe(160);
    expect(subscription?.position).toEqual({ x: 456, y: 72 });
    expect(connector?.data?.waypoints).toEqual([{ x: 360, y: 144 }, { x: 420, y: 144 }]);

    const commands: ModelCommandRequest[] = [];
    const coalescer = createGeometryCoalescer<GeometryUpdate>(
      (_elementId, payload) => commands.push(buildGeometrySetRequest(payload)),
      240,
    );
    coalescer.schedule(`classifier:${sourceId}`, {
      targetType: 'CLASSIFIER', viewId, targetId: sourceId,
      x: 120, y: 96, w: 240, h: 160, zOrder: 1,
    });
    coalescer.flush(`classifier:${sourceId}`);
    coalescer.schedule(`relationship:${relationshipId}`, {
      targetType: 'RELATIONSHIP', viewId, targetId: relationshipId,
      waypoints: [{ x: 384, y: 168 }, { x: 432, y: 168 }],
    });
    coalescer.flush(`relationship:${relationshipId}`);

    expect(commands).toHaveLength(2);
    expect(commands[0]).toEqual({
      type: 'geometry.set',
      payload: { targetType: 'CLASSIFIER', viewId, targetId: sourceId, x: 120, y: 96, w: 240, h: 160, zOrder: 1 },
    });
    expect(commands[1]).toEqual({
      type: 'geometry.set',
      payload: {
        targetType: 'RELATIONSHIP', viewId, targetId: relationshipId,
        waypoints: [{ x: 384, y: 168 }, { x: 432, y: 168 }],
      },
    });
  });
});
