// @ts-expect-error Node built-ins are available in Vitest, but this browser project omits Node type declarations.
import { readFileSync } from 'node:fs';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ModelResource, RelationshipEndResource } from '../api/types';
import { buildFlowEdges } from './UmlCanvas';
import { RelationshipEndLabelAnchor } from './UmlRelationshipEdge';

const classifierAId = '11111111-1111-4111-8111-111111111111';
const classifierBId = '22222222-2222-4222-8222-222222222222';
const relationshipId = '33333333-3333-4333-8333-333333333333';
const viewId = '44444444-4444-4444-8444-444444444444';

const sourceEnd: RelationshipEndResource = {
  id: '55555555-5555-4555-8555-555555555555',
  classifierId: classifierAId,
  role: 'dueño',
  multiplicity: 'EXACTLY_ONE',
  navigability: 'UNSPECIFIED',
  aggregationKind: 'NONE',
  ordinal: 1,
};

const targetEnd: RelationshipEndResource = {
  id: '66666666-6666-4666-8666-666666666666',
  classifierId: classifierBId,
  role: null,
  multiplicity: 'ZERO_OR_MANY',
  navigability: 'NAVIGABLE',
  aggregationKind: 'NONE',
  ordinal: 2,
};

function renderAnchor(side: 'source' | 'target'): string {
  return renderToStaticMarkup(createElement(RelationshipEndLabelAnchor, {
    side,
    end: side === 'source' ? sourceEnd : targetEnd,
    position: { x: 12, y: 34, estimatedBounds: { x: 0, y: 0, width: 40, height: 17 } },
  }));
}

describe('RelationshipEndLabelAnchor', () => {
  it('binds the source label hit area to end ordinal 1 (end A)', () => {
    const markup = renderAnchor('source');
    expect(markup).toContain('uml-edge-label-position');
    expect(markup).toContain('uml-edge-label-position--end');
    expect(markup).toContain('data-end-ordinal="1"');
    expect(markup).toContain('dueño');
  });

  it('binds the target label hit area to end ordinal 2 (end B)', () => {
    const markup = renderAnchor('target');
    expect(markup).toContain('uml-edge-label-position--end');
    expect(markup).toContain('data-end-ordinal="2"');
    expect(markup).toContain('0..*');
  });

  it('positions the hit area at the label placement', () => {
    const markup = renderAnchor('source');
    expect(markup).toMatch(/translate\(-50%, -50%\) translate\(12px, 34px\)/);
  });

  it('keeps the overlay inert except for the end-label hit areas', () => {
    // This intentionally checks stylesheet text: hit-testing is a CSS fact, not something SSR markup can prove.
    const stylesheet = readFileSync(new URL('../styles.css', import.meta.url), 'utf8');
    const baseRule = stylesheet.match(/\.uml-edge-label-position\s*\{([^}]*)\}/)?.[1] ?? '';
    expect(baseRule).toMatch(/pointer-events:\s*none\s*;/);
    const endRule = stylesheet.match(/\.uml-edge-label-position--end\s*\{([^}]*)\}/)?.[1] ?? '';
    expect(endRule).toMatch(/pointer-events:\s*auto\s*;/);
  });
});

describe('buildFlowEdges interaction threading', () => {
  const model: ModelResource = {
    id: '77777777-7777-4777-8777-777777777777',
    projectId: '88888888-8888-4888-8888-888888888888',
    revision: 1,
    versionId: '99999999-9999-4999-8999-999999999999',
    views: [{ id: viewId, name: 'main', is_default: true, element_geometry: [], connector_path: [] }],
    classifiers: [],
    relationships: [{
      id: relationshipId,
      kind: 'ASSOCIATION',
      name: 'posee',
      constraints: [],
      ends: [sourceEnd, targetEnd],
    }],
  };

  it('threads the context-menu opener and endpoint reporter into the edge data when provided', () => {
    const onRelationshipContextMenu = () => undefined;
    const onEdgeEndpointsChange = () => undefined;
    const edge = buildFlowEdges(model, undefined, { onRelationshipContextMenu, onEdgeEndpointsChange })
      .find((candidate) => candidate.id === relationshipId);
    expect(edge?.data?.onRelationshipContextMenu).toBe(onRelationshipContextMenu);
    expect(edge?.data?.onEdgeEndpointsChange).toBe(onEdgeEndpointsChange);
  });

  it('omits the interaction callbacks when the opener does not provide them', () => {
    const edge = buildFlowEdges(model).find((candidate) => candidate.id === relationshipId);
    expect(edge?.data?.onRelationshipContextMenu).toBeUndefined();
    expect(edge?.data?.onEdgeEndpointsChange).toBeUndefined();
    expect(edge?.data?.onRouteAdjustmentChange).toBeUndefined();
  });

  it('threads the route-adjustment callback like the other interaction callbacks', () => {
    const onRouteAdjustmentChange = () => undefined;
    const edge = buildFlowEdges(model, undefined, { onRouteAdjustmentChange })
      .find((candidate) => candidate.id === relationshipId);
    expect(edge?.data?.onRouteAdjustmentChange).toBe(onRouteAdjustmentChange);
  });

  it('threads the stored routeAdjustment from the view_style row into the edge style', () => {
    const adjustment = { axis: 'H' as const, corridors: [24], levels: [-24] };
    const styledModel: ModelResource = {
      ...model,
      view_style: [{
        viewId,
        targetId: relationshipId,
        routing: 'ORTHOGONAL',
        cornerRadius: 6,
        strokeWidth: 2,
        dashed: false,
        routeAdjustment: adjustment,
      }],
    };
    const edge = buildFlowEdges(styledModel).find((candidate) => candidate.id === relationshipId);
    expect(edge?.data?.style?.routeAdjustment).toEqual(adjustment);
  });

  it('keeps threading routeAdjustment.anchors into the edge style', () => {
    const anchors = { source: { side: 'E' as const, offset: 12 }, target: { side: 'W' as const, offset: -12 } };
    const anchoredModel: ModelResource = {
      ...model,
      view_style: [{
        viewId,
        targetId: relationshipId,
        routing: 'ORTHOGONAL',
        cornerRadius: 0,
        strokeWidth: 2,
        dashed: false,
        routeAdjustment: { axis: 'H', corridors: [24], levels: [], anchors },
      }],
    };
    const edge = buildFlowEdges(anchoredModel).find((candidate) => candidate.id === relationshipId);
    expect(edge?.data?.style?.routeAdjustment?.anchors).toEqual(anchors);
  });

  it('defaults routeAdjustment to null when the row has none or there is no row', () => {
    const rowWithoutAdjustment: ModelResource = {
      ...model,
      view_style: [{
        viewId,
        targetId: relationshipId,
        routing: 'FREE',
        cornerRadius: 0,
        strokeWidth: 2,
        dashed: false,
      }],
    };
    const fromRow = buildFlowEdges(rowWithoutAdjustment).find((candidate) => candidate.id === relationshipId);
    expect(fromRow?.data?.style?.routeAdjustment).toBeNull();
    const plain = buildFlowEdges(model).find((candidate) => candidate.id === relationshipId);
    expect(plain?.data?.style?.routeAdjustment).toBeNull();
  });
});
