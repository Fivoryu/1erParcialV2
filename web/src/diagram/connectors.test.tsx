// @ts-expect-error Node built-ins are available in Vitest, but this browser project omits Node type declarations.
import { readFileSync } from 'node:fs';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { ReactFlow } from '@xyflow/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ModelResource, RelationshipEndResource, RelationshipResource } from '../api/types';
import {
  applyOptimisticCommand,
  buildConstraintAddRequest,
  buildConstraintRemoveRequest,
  buildConstraintUpdateRequest,
  buildGeometrySetRequest,
  buildModelCommandEnvelope,
  buildRelationshipDeleteRequest,
  buildRelationshipUpdateRequest,
  RELATIONSHIP_KINDS,
  type GeometryUpdate,
  type ModelCommandRequest,
} from './commands';
import { createGeometryCoalescer } from './geometry-coalescer';
import { buildFlowEdges, buildFlowNodes } from './UmlCanvas';
import { endLabelsApplyTo } from './uml-format';
import { UmlClassNode } from './UmlClassNode';
import {
  buildRelationshipEdgePath,
  estimateLabelWidth,
  DIAMOND_BEHIND_ARROW_PATH,
  DIAMOND_PATH,
  HOLLOW_TRIANGLE_MARKER,
  OPEN_ARROW_PATH,
  hollowTriangleGeometry,
  markerClearance,
  routingObstacles,
  placeRelationshipLabels,
  RelationshipEndLabels,
  relationshipMarkerPresentation,
} from './UmlRelationshipEdge';
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
  constraints: [{ id: '77777777-7777-4777-8777-777777777777', expression: 'orders->notEmpty()', severity: 'WARNING' }],
};

const model: ModelResource = {
  id: '77777777-7777-4777-8777-777777777777',
  projectId: '88888888-8888-4888-8888-888888888888',
  revision: 5,
  versionId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
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
  ['AGGREGATION', 'solid', 'hollow-diamond-open-arrow', null, false],
  ['COMPOSITION', 'solid', 'filled-diamond-open-arrow', null, false],
  ['GENERALIZATION', 'solid', null, 'hollow-triangle', false],
  ['INTERFACE_REALIZATION', 'dashed', null, 'hollow-triangle', true],
  ['DEPENDENCY', 'dashed', 'open-arrow', null, false],
] as const;

const navigationMarkerCases = [
  ['ASSOCIATION', 'open-arrow', 'open-arrow', null, null, false],
  ['AGGREGATION', 'hollow-diamond-open-arrow', 'open-arrow', 'hollow-diamond', null, false],
  ['COMPOSITION', 'filled-diamond-open-arrow', 'open-arrow', 'filled-diamond', null, false],
  ['GENERALIZATION', null, 'hollow-triangle', null, 'hollow-triangle', true],
  ['INTERFACE_REALIZATION', null, 'hollow-triangle', null, 'hollow-triangle', true],
  ['DEPENDENCY', 'open-arrow', 'open-arrow', null, null, false],
] as const;

const labelPlacementCases = [
  { label: 'very short straight gap', gap: 40, waypoints: false },
  { label: 'very short waypoint gap', gap: 40, waypoints: true },
  { label: 'medium straight gap', gap: 320, waypoints: false },
  { label: 'medium waypoint gap', gap: 320, waypoints: true },
  { label: 'long straight gap', gap: 600, waypoints: false },
  { label: 'long waypoint gap', gap: 600, waypoints: true },
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
        expect(presentation.sourceMarker).toContain(kind === 'AGGREGATION' ? 'hollow-diamond' : 'filled-diamond');
      } else {
        expect(diamonds).toEqual([]);
      }
    },
  );

  it.each(navigationMarkerCases)(
    '%s changes with navigability except for inheritance and realization triangles',
    (kind, navigableSourceMarker, navigableTargetMarker, nonNavigableSourceMarker, nonNavigableTargetMarker, fixedByKind) => {
      const navigableSource = { ...sourceEnd, navigability: 'NAVIGABLE' as const };
      const nonNavigableSource = { ...sourceEnd, navigability: 'NOT_NAVIGABLE' as const };
      const navigableTarget = { ...targetEnd, navigability: 'NAVIGABLE' as const };
      const nonNavigableTarget = { ...targetEnd, navigability: 'NOT_NAVIGABLE' as const };
      const fromSource = relationshipMarkerPresentation(kind, [navigableSource, nonNavigableTarget]);
      const fromTarget = relationshipMarkerPresentation(kind, [nonNavigableSource, navigableTarget]);
      const neither = relationshipMarkerPresentation(kind, [nonNavigableSource, nonNavigableTarget]);

      expect(fromSource.sourceMarker).toBe(navigableSourceMarker);
      expect(fromSource.targetMarker).toBe(nonNavigableTargetMarker);
      expect(fromTarget.sourceMarker).toBe(nonNavigableSourceMarker);
      expect(fromTarget.targetMarker).toBe(navigableTargetMarker);
      if (fixedByKind) {
        expect(fromSource).toEqual(fromTarget);
        expect(fromSource).toEqual(neither);
      } else {
        expect(fromSource).not.toEqual(neither);
        expect(fromTarget).not.toEqual(neither);
      }
    },
  );

  it('builds and projects relationship-owned constraint add, update, and removal commands', () => {
    const baseModel: ModelResource = {
      ...model,
      relationships: [{ ...relationship, constraints: [] }],
    };
    const generatedIds = [
      'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab',
      'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaac',
      'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaad',
      'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaae',
      'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaf',
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbc',
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbd',
    ];
    let generatedIndex = 0;
    const uuidFactory = () => generatedIds[generatedIndex++]!;
    const add = buildModelCommandEnvelope(baseModel, buildConstraintAddRequest(
      'RELATIONSHIP', relationshipId, 'orders->notEmpty()', 'WARNING',
    ), uuidFactory);
    expect(add.targets).toEqual([{ scope: 'relationship', id: relationshipId }]);
    expect(add.payload).toMatchObject({ ownerType: 'RELATIONSHIP', ownerId: relationshipId });

    const added = applyOptimisticCommand(baseModel, add);
    expect(added.relationships[0]?.constraints).toEqual([{
      id: generatedIds[1], expression: 'orders->notEmpty()', severity: 'WARNING',
    }]);

    const update = buildModelCommandEnvelope(added, buildConstraintUpdateRequest(generatedIds[1]!, {
      expression: 'orders->size() > 0', severity: 'INFO',
    }), uuidFactory);
    const updated = applyOptimisticCommand(added, update);
    expect(updated.relationships[0]?.constraints[0]).toMatchObject({
      expression: 'orders->size() > 0', severity: 'INFO',
    });

    const remove = buildModelCommandEnvelope(updated, buildConstraintRemoveRequest(generatedIds[1]!), uuidFactory);
    expect(applyOptimisticCommand(updated, remove).relationships[0]?.constraints).toEqual([]);
  });

  it('renders both end roles and multiplicities in UML notation without exposing identifiers or enums', () => {
    const markup = renderToStaticMarkup(createElement(RelationshipEndLabels, {
      sourceEnd,
      targetEnd,
      realizationLabelAtTarget: true,
    }));

    expect(markup).toContain('customer 0..*');
    expect(markup).toContain('subscriptions 1..*');
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
    expect(panel).toContain('orders-&gt;notEmpty()');
    expect(panel).toContain('Save constraint');
    expect(panel).toContain('Severity');
    expect(panel).toContain('Warning');
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
      preconditions: [{ kind: 'cascade_count', expect: 4, scope: null }],
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

describe('relationship label geometry', () => {
  it.each(labelPlacementCases)('$label keeps every estimated label box clear of both class rectangles', ({ gap, waypoints }) => {
    const sourceRectangle = { x: 0, y: 0, width: 100, height: 120 };
    const targetRectangle = { x: 100 + gap, y: 0, width: 100, height: 120 };
    const sourceBorder = { x: sourceRectangle.x + sourceRectangle.width, y: 60 };
    const targetBorder = { x: targetRectangle.x, y: 60 };
    const points = [
      sourceBorder,
      ...(waypoints ? [
        { x: sourceBorder.x + gap / 3, y: 72 },
        { x: sourceBorder.x + gap * 2 / 3, y: 72 },
      ] : []),
      targetBorder,
    ];
    const placements = placeRelationshipLabels(points, sourceRectangle, targetRectangle, {
      source: 'customer 0..*',
      target: 'subscriptions 1..*',
      name: 'owns',
    });
    const intersects = (
      left: { x: number; y: number; width: number; height: number },
      right: { x: number; y: number; width: number; height: number },
    ) => left.x < right.x + right.width
      && left.x + left.width > right.x
      && left.y < right.y + right.height
      && left.y + left.height > right.y;

    expect(estimateLabelWidth('UML')).toBe(28);
    for (const placement of Object.values(placements)) {
      if (!placement) {
        continue;
      }
      for (const rectangle of [sourceRectangle, targetRectangle]) {
        expect(intersects(placement.estimatedBounds, rectangle)).toBe(false);
        expect(intersects(placement.estimatedBounds, {
          x: rectangle.x - 8,
          y: rectangle.y - 8,
          width: rectangle.width + 16,
          height: rectangle.height + 16,
        })).toBe(false);
      }
    }

    if (gap === 40) {
      expect(placements.source).not.toBeNull();
      expect(placements.target).not.toBeNull();
      expect(placements.name).not.toBeNull();
    } else {
      expect(placements.source).not.toBeNull();
      expect(placements.target).not.toBeNull();
    }

    const visibleBounds = Object.values(placements)
      .filter((placement) => placement !== null)
      .map((placement) => placement.estimatedBounds);
    for (let left = 0; left < visibleBounds.length; left += 1) {
      for (let right = left + 1; right < visibleBounds.length; right += 1) {
        expect(intersects(visibleBounds[left]!, visibleBounds[right]!)).toBe(false);
      }
    }
  });
});

describe('routing obstacles', () => {
  const node = (id: string, x: number, kind = 'CLASS', size: { width?: number; height?: number } = { width: 100, height: 60 }) => ({
    id,
    position: { x, y: 10 },
    ...size,
    data: { classifier: { kind } },
  });

  it('takes every class except the two ends of the relationship', () => {
    const obstacles = routingObstacles([node('a', 0), node('b', 200), node('c', 400)], ['a', 'c']);

    expect(obstacles).toEqual([{ x: 200, y: 10, width: 100, height: 60 }]);
  });

  it('leaves packages out, because the classes they contain are not blocked by them', () => {
    expect(routingObstacles([node('p', 0, 'PACKAGE'), node('b', 200)], [])).toEqual([{ x: 200, y: 10, width: 100, height: 60 }]);
  });

  it('prefers the measured size and skips a node that has none yet', () => {
    const measured = { ...node('m', 0), measured: { width: 120, height: 90 } };

    expect(routingObstacles([measured, node('unsized', 300, 'CLASS', {})], [])).toEqual([{ x: 0, y: 10, width: 120, height: 90 }]);
  });
});

describe('end labels clear the marker', () => {
  const sourceRectangle = { x: 0, y: 0, width: 100, height: 120 };
  const targetRectangle = { x: 400, y: 0, width: 100, height: 120 };
  const points = [{ x: 100, y: 60 }, { x: 400, y: 60 }];

  it('keeps the source label past the marker drawn at that end of the line', () => {
    const plain = placeRelationshipLabels(points, sourceRectangle, targetRectangle, { source: '1', target: '', name: '' });
    const cleared = placeRelationshipLabels(points, sourceRectangle, targetRectangle, { source: '1', target: '', name: '' }, { source: 30, target: 0 });

    expect(cleared.source!.estimatedBounds.x).toBeGreaterThanOrEqual(plain.source!.estimatedBounds.x + 30);
  });

  it('sizes the clearance from the marker and the stroke width it scales with', () => {
    expect(markerClearance(null, 2)).toBe(0);
    expect(markerClearance('hollow-triangle', 2)).toBeGreaterThan(markerClearance('open-arrow', 2));
    expect(markerClearance('filled-diamond', 4)).toBe(markerClearance('filled-diamond', 2) * 2 - 6);
  });

  it('shows no end labels on generalization, realization or dependency', () => {
    expect(endLabelsApplyTo('ASSOCIATION')).toBe(true);
    expect(endLabelsApplyTo('AGGREGATION')).toBe(true);
    expect(endLabelsApplyTo('COMPOSITION')).toBe(true);
    expect(endLabelsApplyTo('GENERALIZATION')).toBe(false);
    expect(endLabelsApplyTo('INTERFACE_REALIZATION')).toBe(false);
    expect(endLabelsApplyTo('DEPENDENCY')).toBe(false);
  });
});

describe('relationship markers stay outside the class', () => {
  const xs = (path: string) => [...path.matchAll(/-?\d+(?:\.\d+)?/g)]
    .map((match) => Number(match[0]))
    .filter((_, index) => index % 2 === 0);

  it.each([
    ['open arrow', OPEN_ARROW_PATH],
    ['diamond', DIAMOND_PATH],
    ['diamond behind the arrow', DIAMOND_BEHIND_ARROW_PATH],
  ])('draws the %s at non-positive x, never into the class box', (_name, path) => {
    expect(Math.max(...xs(path))).toBeLessThanOrEqual(0);
  });

  it('puts the tip of the open arrow and the vertex of the diamond on the border', () => {
    expect(xs(OPEN_ARROW_PATH)).toContain(0);
    expect(xs(DIAMOND_PATH)).toContain(0);
  });
});

describe('node height follows the card', () => {
  it('uses the stored height only as a minimum, so a class that grew past it is never clipped', () => {
    const node = buildFlowNodes(model, [], () => undefined, () => undefined).find((candidate) => candidate.id === sourceId);

    expect(node?.width).toBe(240);
    expect(node?.height).toBeUndefined();
    expect(node?.style).toEqual({ minHeight: 160 });
  });
});

describe('connection handles', () => {
  it('renders keyboard-focusable source and target handles on all four sides of both classes', () => {
    const nodes = buildFlowNodes(model, [], () => undefined, () => undefined);
    const markup = renderToStaticMarkup(createElement(ReactFlow, {
      nodes,
      edges: [],
      nodeTypes: { umlClass: UmlClassNode },
      fitView: false,
    }));

    const handles = markup.match(/<div\b(?=[^>]*class="[^"]*\breact-flow__handle\b)[^>]*>/g) ?? [];
    expect(handles).toHaveLength(16);
    for (const side of ['left', 'right', 'top', 'bottom']) {
      for (const type of ['source', 'target']) {
        const matchingHandles = handles.filter((handle) => handle.includes(`data-handleid="${type}-${side}"`));
        expect(matchingHandles).toHaveLength(2);
        for (const handle of matchingHandles) {
          expect(handle).toContain(`aria-label="Connection ${type}, ${side}"`);
          expect(handle).toContain('tabindex="0"');
        }
      }
    }
  });

  it('keeps connection handles visibly styled at rest', () => {
    // This intentionally checks stylesheet text: resting visibility is a CSS fact, not something SSR markup can prove.
    const stylesheet = readFileSync(new URL('../styles.css', import.meta.url), 'utf8');
    const ruleStart = stylesheet.indexOf('.uml-flow-node .react-flow__handle');
    const ruleEnd = stylesheet.indexOf('}', ruleStart);
    const restingHandleRule = stylesheet.slice(ruleStart, ruleEnd + 1);

    expect(ruleStart).toBeGreaterThanOrEqual(0);
    expect(restingHandleRule).not.toMatch(/\bopacity\s*:\s*0\s*;/);
    expect(restingHandleRule).toMatch(/\bopacity\s*:\s*0\.\d+\s*;/);
    expect(stylesheet).toMatch(/\.react-flow__node:focus-within \.react-flow__handle\s*\{[^}]*opacity:\s*1\s*;/);
  });
});

describe('interface realization triangle geometry', () => {
  it.each([
    {
      label: 'straight edge',
      points: [{ x: 80, y: 120 }, { x: 360, y: 120 }],
      targetBorder: { x: 360, y: 120 },
    },
    {
      label: 'waypoint edge',
      points: [{ x: 80, y: 120 }, { x: 220, y: 160 }, { x: 360, y: 220 }],
      targetBorder: { x: 360, y: 220 },
    },
  ])('$label anchors the apex at the target border and points the base away along the final segment', ({ points, targetBorder }) => {
    const path = buildRelationshipEdgePath(points);
    const marker = hollowTriangleGeometry(points)!;
    const previous = points[points.length - 2]!;
    const finalSegment = { x: targetBorder.x - previous.x, y: targetBorder.y - previous.y };
    const segmentLength = Math.hypot(finalSegment.x, finalSegment.y);
    const baseVector = { x: marker.baseCenter.x - marker.apex.x, y: marker.baseCenter.y - marker.apex.y };

    expect(path.split(' L ').at(-1)).toBe(`${targetBorder.x} ${targetBorder.y}`);
    expect(marker.pathEnd).toEqual(targetBorder);
    expect(marker.apex).toEqual(targetBorder);
    expect(HOLLOW_TRIANGLE_MARKER.refX).toBe(0);
    expect(HOLLOW_TRIANGLE_MARKER.refY).toBe(0);
    expect(HOLLOW_TRIANGLE_MARKER.orient).toBe('auto');
    expect(HOLLOW_TRIANGLE_MARKER.markerUnits).toBe('strokeWidth');
    expect(HOLLOW_TRIANGLE_MARKER.path).toMatch(/^M 0 0 L -13 -7 L -13 7 Z$/);
    expect(Math.cos(marker.orientationRadians)).toBeCloseTo(finalSegment.x / segmentLength);
    expect(Math.sin(marker.orientationRadians)).toBeCloseTo(finalSegment.y / segmentLength);
    expect(baseVector.x * finalSegment.x + baseVector.y * finalSegment.y).toBeLessThan(0);
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
    expect(customer?.style?.minHeight).toBe(160);
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

describe('edge selection across a model sync', () => {
  it('keeps the edge that was selected when the edges are rebuilt', () => {
    const first = model.relationships[0]!;
    const twin = { ...first, id: 'twin-relationship' };
    const synced = { ...model, relationships: [first, twin] };
    const before = buildFlowEdges(synced).map((edge) => (edge.id === first.id ? { ...edge, selected: true } : edge));

    const after = buildFlowEdges(synced, undefined, undefined, before);

    expect(after.map((edge) => [edge.id, edge.selected ?? false])).toEqual([[first.id, true], [twin.id, false]]);
  });
});

describe('parallel relationship lanes', () => {
  it('gives each relationship between the same two classes its own lane on the edge data', () => {
    const first = model.relationships[0]!;
    const twin = { ...first, id: 'twin-relationship' };
    const lonely = buildFlowEdges(model).find((edge) => edge.id === first.id);
    const paired = buildFlowEdges({ ...model, relationships: [first, twin] });

    expect(lonely?.data?.lane).toBe(0);
    expect(paired.map((edge) => edge.data?.lane)).toEqual([-8, 8]);
  });
});

describe('relationship route & style (IA4-07)', () => {
  const styledModel: ModelResource = {
    ...model,
    view_style: [{
      viewId,
      targetId: relationshipId,
      routing: 'ORTHOGONAL',
      cornerRadius: 8,
      strokeWidth: 3,
      dashed: true,
      routeAdjustment: { axis: 'H', corridors: [40], levels: [-12] },
    }],
  };

  it('propagates the per-view style row into the flow edge data and defaults to straight', () => {
    const connector = buildFlowEdges(styledModel).find((edge) => edge.id === relationshipId);
    expect(connector?.data?.style).toEqual({
      routing: 'ORTHOGONAL',
      cornerRadius: 8,
      strokeWidth: 3,
      dashed: true,
      routeAdjustment: { axis: 'H', corridors: [40], levels: [-12] },
    });

    const plain = buildFlowEdges(model).find((edge) => edge.id === relationshipId);
    expect(plain?.data?.style).toEqual({
      routing: 'ORTHOGONAL',
      cornerRadius: 0,
      strokeWidth: 2,
      dashed: false,
      routeAdjustment: null,
    });
  });

  it('threads the stored anchors into the style and picks the connection handles from those sides', () => {
    const anchoredModel: ModelResource = {
      ...model,
      view_style: [{
        viewId,
        targetId: relationshipId,
        routing: 'ORTHOGONAL',
        cornerRadius: 0,
        strokeWidth: 2,
        dashed: false,
        routeAdjustment: {
          axis: 'H',
          corridors: [40],
          levels: [],
          anchors: { source: { side: 'N', offset: 30 }, target: { side: 'N', offset: -30 } },
        },
      }],
    };
    const edge = buildFlowEdges(anchoredModel).find((candidate) => candidate.id === relationshipId);
    expect(edge?.data?.style?.routeAdjustment?.anchors)
      .toEqual({ source: { side: 'N', offset: 30 }, target: { side: 'N', offset: -30 } });
    // The classes sit side by side (auto = right/left), but the stored sides win (ANC-06).
    expect(edge?.sourceHandle).toBe('source-top');
    expect(edge?.targetHandle).toBe('target-top');
  });

  it('renders the route & style controls reflecting the persisted view style row', () => {
    const panel = renderToStaticMarkup(createElement(PropertiesPanel, {
      model: styledModel,
      selection: { kind: 'relationship', relationshipId },
      onCommand: () => null,
      onDelete: () => undefined,
      onSelect: () => undefined,
    }));

    expect(panel).toContain('Route &amp; style');
    expect(panel).toContain('Reset route');
    expect(panel).toMatch(/value="ORTHOGONAL"[^>]*selected/);
    expect(panel).toMatch(/id="relationship-corner-radius"[^>]*value="8"/);
    expect(panel).toMatch(/id="relationship-stroke-width"[^>]*value="3"/);
    expect(panel).toMatch(/id="relationship-dashed"[^>]*checked/);
  });

  it('falls back to orthogonal, solid defaults when no style row exists', () => {
    const panel = renderToStaticMarkup(createElement(PropertiesPanel, {
      model,
      selection: { kind: 'relationship', relationshipId },
      onCommand: () => null,
      onDelete: () => undefined,
      onSelect: () => undefined,
    }));

    expect(panel).toMatch(/value="ORTHOGONAL"[^>]*selected/);
    expect(panel).toMatch(/id="relationship-corner-radius"[^>]*value="0"/);
    expect(panel).toMatch(/id="relationship-stroke-width"[^>]*value="2"/);
    expect(panel).not.toMatch(/id="relationship-dashed"[^>]*checked/);
  });
});
