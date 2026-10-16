import { describe, expect, it, vi } from 'vitest';
import { createApiClient } from './client';

/**
 * T7/L47 contract: the session-based chat API on the client. The web chat posts to the diagram's
 * General session instead of the legacy project-level endpoints; membership stays server-enforced.
 */
const projectId = '55555555-5555-4555-8555-555555555555';
const viewId = '66666666-6666-4666-8666-666666666666';
const sessionId = '77777777-7777-4777-8777-777777777777';

function clientWithBody(body: unknown) {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  }));
  return { fetcher, client: createApiClient('https://diagram.test', fetcher) };
}

describe('session chat client (T7)', () => {
  it('lists the sessions of a diagram view', async () => {
    const sessions = [{
      id: sessionId,
      viewId,
      title: 'General',
      createdByUserId: '88888888-8888-4888-8888-888888888888',
      createdAt: '2026-02-01T15:00:00Z',
    }];
    const { fetcher, client } = clientWithBody(sessions);

    const result = await client.listSessions(projectId, viewId);

    expect(result).toEqual(sessions);
    // requestJson relies on fetch's GET default; the init only carries the auth headers.
    expect(fetcher).toHaveBeenCalledWith(
      `https://diagram.test/projects/${projectId}/views/${viewId}/sessions`,
      expect.objectContaining({ headers: expect.any(Headers) }),
    );
  });

  it('posts a chat message into a session', async () => {
    const message = {
      id: '99999999-9999-4999-8999-999999999999',
      sessionId,
      kind: 'HUMAN',
      authorUserId: '88888888-8888-4888-8888-888888888888',
      authorDisplayName: 'Morgan Reader',
      body: 'Mensaje dictado y enviado',
      createdAt: '2026-02-01T16:00:00Z',
      replyTo: null,
    };
    const { fetcher, client } = clientWithBody(message);

    const result = await client.postSessionMessage(projectId, sessionId, 'Mensaje dictado y enviado');

    expect(result).toEqual(message);
    expect(fetcher).toHaveBeenCalledWith(
      `https://diagram.test/projects/${projectId}/sessions/${sessionId}/messages`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ body: 'Mensaje dictado y enviado' }),
      }),
    );
  });
});
