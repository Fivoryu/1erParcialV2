import { describe, expect, it, vi } from 'vitest';
import { createApiClient } from './client';
import type { XmiImportResource, XmiPreviewResource } from './types';

const projectId = '22222222-2222-4222-8222-222222222222';
const fileBase64 = 'PHhtaT7p';
const preview: XmiPreviewResource = {
  counts: {
    classifiers: 3,
    relationships: 2,
    views: 1,
    figurePlacements: 3,
    connectorPlacements: 2,
  },
  losses: [{ name: 'connector_path_not_imported', count: 1 }],
  disagreements: [],
  checksum: 'a'.repeat(64),
};
const imported: XmiImportResource = {
  outcome: 'ACCEPTED',
  command: {
    operationId: '33333333-3333-4333-8333-333333333333',
    status: 'ACCEPTED',
    resultRevision: 5,
    diagnostics: [],
    authorizationClassification: null,
  },
  exchangeId: '44444444-4444-4444-8444-444444444444',
  losses: preview.losses,
  disagreements: [],
};

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('XMI API client', () => {
  it('previews a project-scoped file using JSON base64', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(preview));
    const client = createApiClient('https://diagram.test', fetcher);

    await expect(client.previewXmi(projectId, { fileBase64 })).resolves.toEqual(preview);

    expect(fetcher).toHaveBeenCalledWith(
      `https://diagram.test/projects/${projectId}/xmi/preview`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ fileBase64 }),
      }),
    );
    // The shared request helper hands fetch a Headers instance, so the content type is read through it.
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Content-Type')).toBe('application/json');
  });

  it('confirms the same base64 payload with the preview checksum and chosen mode', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(imported));
    const client = createApiClient('https://diagram.test', fetcher);
    const request = {
      fileBase64,
      checksum: preview.checksum,
      mode: 'AGREGAR' as const,
      idempotencyKey: 'xmi-import-attempt-1',
    };

    await expect(client.importXmi(projectId, request)).resolves.toEqual(imported);

    expect(fetcher).toHaveBeenCalledWith(
      `https://diagram.test/projects/${projectId}/xmi/import`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(request),
      }),
    );
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Content-Type')).toBe('application/json');
  });
});
