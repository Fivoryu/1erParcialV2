import { describe, expect, it, vi } from 'vitest';
import { createApiClient } from '../api/client';
import type { ModelResource } from '../api/types';
import {
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
