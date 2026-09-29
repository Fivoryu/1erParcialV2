import { describe, expect, it, vi } from 'vitest';
import { createApiClient } from '../api/client';
import type { ModelResource } from '../api/types';
import { buildClassifierCreateEnvelope } from './commands';

const model: ModelResource = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  revision: 12,
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
});
