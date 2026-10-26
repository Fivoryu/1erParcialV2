import { describe, expect, it, vi } from 'vitest';
import { createApiClient } from '../api/client';
import type { ModelResource, OperationEnvelope, ViewStyleResource } from '../api/types';
import {
  buildAnchorStyleRequest,
  applyOptimisticCommand,
  buildClassifierCreateEnvelope,
  buildStyleSetRequest,
  mergeViewStyleUpdate,
  type RoutingMode,
  type ViewStyleValues,
} from './commands';

const model: ModelResource = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  revision: 12,
  versionId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  views: [],
  classifiers: [],
  relationships: [],
};

function sequenceUuidFactory(...values: string[]) {
  let index = 0;
  return () => {
    const value = values[index];
    if (value === undefined) {
      throw new Error('The test UUID sequence is exhausted.');
    }
    index += 1;
    return value;
  };
}

describe('palette classifier command', () => {
  it('posts a client-generated classifier id with the current base revision', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({
      operationId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      status: 'ACCEPTED',
      resultRevision: 13,
      diagnostics: [],
      authorizationClassification: 'AUTHORIZED_ONLINE',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const api = createApiClient('https://diagram.test', fetcher);
    const envelope = buildClassifierCreateEnvelope(model, 'CLASS', sequenceUuidFactory(
      'cccccccc-cccc-4ccc-8ccc-cccccccccccd',
      'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
      'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
    ));

    const outcome = await api.executeCommand(model.projectId, envelope);

    expect(outcome.kind).toBe('accepted');
    expect(fetcher).toHaveBeenCalledWith(
      `https://diagram.test/projects/${model.projectId}/commands`,
      expect.objectContaining({ method: 'POST' }),
    );
    const request = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body)) as {
      baseRevision: number;
      payload: { id: string; kind: string };
    };
    expect(request.baseRevision).toBe(12);
    expect(request.payload.id).toBe('dddddddd-dddd-4ddd-8ddd-dddddddddddd');
    expect(request.payload.kind).toBe('CLASS');
    expect(request.payload.id).not.toBe(envelope.operationId);
  });

  it('sends a package create command with its client-generated id', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({
      operationId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccd',
      status: 'ACCEPTED',
      resultRevision: 13,
      diagnostics: [],
      authorizationClassification: 'AUTHORIZED_ONLINE',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const api = createApiClient('https://diagram.test', fetcher);
    const envelope = buildClassifierCreateEnvelope(model, 'PACKAGE', sequenceUuidFactory(
      'cccccccc-cccc-4ccc-8ccc-ccccccccccce',
      'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
      'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
    ));

    const outcome = await api.executeCommand(model.projectId, envelope);

    expect(outcome.kind).toBe('accepted');
    const request = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body)) as {
      type: string;
      payload: { id: string; kind: string; namespaceId: string | null };
    };
    expect(request.type).toBe('classifier.create');
    expect(request.payload).toEqual({ id: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', name: 'Package 1', kind: 'PACKAGE', namespaceId: null });
    expect(request.payload.id).not.toBe(envelope.operationId);
  });
});

describe('style.set command builder', () => {
  it('builds a style.set request with the whitelisted payload and no targets', () => {
    const request = buildStyleSetRequest({
      viewId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
      targetId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
      routing: 'ORTHOGONAL',
      cornerRadius: 8,
      strokeWidth: 3,
      dashed: true,
      routeAdjustment: { axis: 'H', corridors: [40], levels: [-12] },
    });
    expect(request.type).toBe('style.set');
    expect(request.targets).toBeUndefined();
    expect(request.payload).toEqual({
      viewId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
      targetId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
      routing: 'ORTHOGONAL',
      cornerRadius: 8,
      strokeWidth: 3,
      dashed: true,
      routeAdjustment: { axis: 'H', corridors: [40], levels: [-12] },
    });
  });

  it('carries the anchors inside the routeAdjustment payload', () => {
    const anchors = { source: { side: 'E' as const, offset: -24 }, target: { side: 'W' as const, offset: 12 } };
    const request = buildStyleSetRequest({
      viewId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
      targetId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
      routing: 'ORTHOGONAL',
      cornerRadius: 0,
      strokeWidth: 2,
      dashed: false,
      routeAdjustment: { axis: 'H', corridors: [0], levels: [], anchors },
    });
    expect(request.payload?.routeAdjustment).toEqual({
      axis: 'H',
      corridors: [0],
      levels: [],
      anchors: { source: { side: 'E', offset: -24 }, target: { side: 'W', offset: 12 } },
    });
  });

  it('carries the explicit null routeAdjustment so the panel can clear it', () => {
    const request = buildStyleSetRequest({
      viewId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
      targetId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
      routing: 'ORTHOGONAL',
      cornerRadius: 0,
      strokeWidth: 2,
      dashed: false,
      routeAdjustment: null,
    });
    expect(request.payload?.routeAdjustment).toBeNull();
  });
});

describe('mergeViewStyleUpdate', () => {
  const viewId = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
  const targetId = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee';
  const adjustment = { axis: 'H' as const, corridors: [40], levels: [-12] };
  const current: ViewStyleValues = {
    routing: 'ORTHOGONAL',
    cornerRadius: 0,
    strokeWidth: 2,
    dashed: false,
    routeAdjustment: adjustment,
  };

  it('keeps the stored adjustment when the override does not mention it', () => {
    const merged = mergeViewStyleUpdate(viewId, targetId, current, { cornerRadius: 8 });
    expect(merged).toEqual({
      viewId,
      targetId,
      routing: 'ORTHOGONAL',
      cornerRadius: 8,
      strokeWidth: 2,
      dashed: false,
      routeAdjustment: { axis: 'H', corridors: [40], levels: [-12] },
    });
    expect(merged.routeAdjustment).toEqual(adjustment);
  });

  it('clears the adjustment when the override carries an explicit null', () => {
    const merged = mergeViewStyleUpdate(viewId, targetId, current, { routeAdjustment: null });
    expect(merged.routing).toBe('ORTHOGONAL');
    expect(merged.routeAdjustment).toBeNull();
  });

  it('nulls the adjustment in the same payload when the routing leaves ORTHOGONAL', () => {
    for (const routing of ['FREE', 'STRAIGHT'] as const) {
      const merged = mergeViewStyleUpdate(viewId, targetId, current, { routing });
      expect(merged.routing).toBe(routing satisfies RoutingMode);
      expect(merged.routeAdjustment).toBeNull();
    }
  });

  it('keeps the passed viewId/targetId even if current tries to override them', () => {
    const impostor = { ...current, viewId: 'hack-view', targetId: 'hack-rel' } as ViewStyleValues;
    const merged = mergeViewStyleUpdate(viewId, targetId, impostor, {});
    expect(merged.viewId).toBe(viewId);
    expect(merged.targetId).toBe(targetId);
  });
});

describe('applyOptimisticCommand style.set upsert', () => {
  const styleEnvelope = (payload: Record<string, unknown>): OperationEnvelope => ({
    operationId: '11111111-1111-4111-8111-111111111111',
    type: 'style.set',
    author: { userId: '22222222-2222-4222-8222-222222222222', externalSubject: 'tester' },
    projectId: model.projectId,
    modelId: model.id,
    baseRevision: model.revision,
    targets: [],
    preconditions: [],
    payload,
    origin: 'MANUAL',
    causalParentOperationId: null,
    idempotencyKey: '33333333-3333-4333-8333-333333333333',
  });
  const adjustment = { axis: 'H' as const, corridors: [40], levels: [-12] };
  const row = (over: Partial<ViewStyleResource>): ViewStyleResource => ({
    viewId: 'view-1',
    targetId: 'rel-1',
    routing: 'ORTHOGONAL',
    cornerRadius: 8,
    strokeWidth: 3,
    dashed: true,
    routeAdjustment: adjustment,
    ...over,
  });
  const stylePayload = (over: Record<string, unknown> = {}) => ({
    viewId: 'view-1',
    targetId: 'rel-1',
    routing: 'ORTHOGONAL',
    cornerRadius: 8,
    strokeWidth: 3,
    dashed: true,
    routeAdjustment: adjustment,
    ...over,
  });

  it('appends the row when the model has no style row for that view/target', () => {
    const empty: ModelResource = { ...model, view_style: [] };
    const next = applyOptimisticCommand(empty, styleEnvelope(stylePayload()));
    expect(next.view_style).toEqual([row({})]);
    expect(empty.view_style).toEqual([]); // no input mutation
    expect(next.relationships).toEqual(model.relationships);
  });

  it('replaces the existing row for the same (viewId, targetId) and leaves the rest untouched', () => {
    const otherTarget = row({ targetId: 'rel-other' });
    const otherView = row({ viewId: 'view-2' });
    const withRows: ModelResource = {
      ...model,
      view_style: [row({ routing: 'STRAIGHT', routeAdjustment: null }), otherTarget, otherView],
    };
    const next = applyOptimisticCommand(withRows, styleEnvelope(stylePayload()));
    expect(next.view_style).toHaveLength(3);
    expect(next.view_style?.[0]).toEqual(row({}));
    expect(next.view_style?.[1]).toBe(otherTarget);
    expect(next.view_style?.[2]).toBe(otherView);
  });

  it('applies the server defaults when the payload omits the optional fields', () => {
    const next = applyOptimisticCommand(model, styleEnvelope({ viewId: 'view-1', targetId: 'rel-1' }));
    expect(next.view_style).toEqual([
      row({ routing: 'STRAIGHT', cornerRadius: 0, strokeWidth: 2, dashed: false, routeAdjustment: null }),
    ]);
  });

  it('carries the anchors into the optimistic row on create and update', () => {
    const anchoredAdjustment = {
      axis: 'H' as const,
      corridors: [0],
      levels: [],
      anchors: { source: { side: 'E' as const, offset: -24 }, target: { side: 'W' as const, offset: 12 } },
    };
    const payload = stylePayload({ routeAdjustment: anchoredAdjustment });

    const created = applyOptimisticCommand({ ...model, view_style: [] }, styleEnvelope(payload));
    expect(created.view_style?.[0]?.routeAdjustment?.anchors).toEqual({
      source: { side: 'E', offset: -24 },
      target: { side: 'W', offset: 12 },
    });

    const updated = applyOptimisticCommand(
      { ...model, view_style: [row({ routeAdjustment: null })] },
      styleEnvelope(payload));
    expect(updated.view_style).toHaveLength(1);
    expect(updated.view_style?.[0]?.routeAdjustment?.anchors).toEqual({
      source: { side: 'E', offset: -24 },
      target: { side: 'W', offset: 12 },
    });
  });

  it('handles a routeAdjustment without anchors exactly as before', () => {
    const created = applyOptimisticCommand({ ...model, view_style: [] }, styleEnvelope(stylePayload()));
    expect(created.view_style?.[0]?.routeAdjustment).toEqual(adjustment);
    expect(Object.hasOwn(created.view_style?.[0]?.routeAdjustment ?? {}, 'anchors')).toBe(false);
  });

  it('leaves the model unchanged when viewId or targetId is missing', () => {
    const withRow: ModelResource = { ...model, view_style: [row({})] };
    expect(applyOptimisticCommand(withRow, styleEnvelope({ targetId: 'rel-1' }))).toBe(withRow);
    expect(applyOptimisticCommand(withRow, styleEnvelope({ viewId: 'view-1' }))).toBe(withRow);
    expect(applyOptimisticCommand(withRow, styleEnvelope({}))).toBe(withRow);
    expect(withRow.view_style).toHaveLength(1);
  });
});

describe('buildAnchorStyleRequest', () => {
  it('stores the chosen sides as an orthogonal route anchored at the centre of each side', () => {
    const request = buildAnchorStyleRequest('view-1', 'rel-1', { source: 'S', target: 'W' });

    expect(request.type).toBe('style.set');
    expect(request.payload).toMatchObject({
      viewId: 'view-1',
      targetId: 'rel-1',
      routing: 'ORTHOGONAL',
      routeAdjustment: {
        axis: 'V',
        corridors: [0],
        levels: [],
        anchors: { source: { side: 'S', offset: 0 }, target: { side: 'W', offset: 0 } },
      },
    });
  });

  it('follows the axis of the source side', () => {
    expect((buildAnchorStyleRequest('v', 'r', { source: 'E', target: 'N' }).payload as { routeAdjustment: { axis: string } })
      .routeAdjustment.axis).toBe('H');
  });
});
