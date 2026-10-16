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

describe('session chat client (T7/T9)', () => {
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

  it('creates a session and reads the history from that session endpoint', async () => {
    const session = {
      id: sessionId,
      viewId,
      title: 'Session 2',
      createdByUserId: '88888888-8888-4888-8888-888888888888',
      createdAt: '2026-02-01T17:00:00Z',
    };
    const message = {
      id: '99999999-9999-4999-8999-999999999999',
      sessionId,
      kind: 'HUMAN',
      authorUserId: '88888888-8888-4888-8888-888888888888',
      authorDisplayName: 'Morgan Reader',
      body: 'Only this session',
      createdAt: '2026-02-01T17:01:00Z',
      replyTo: null,
      attachments: [],
    };
    const { fetcher, client } = clientWithBody(session);

    expect(await client.createSession(projectId, viewId, 'Session 2')).toEqual(session);
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify([message]), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    expect(await client.listSessionMessages(projectId, sessionId)).toEqual([message]);

    expect(fetcher.mock.calls[0]?.[0]).toBe(`https://diagram.test/projects/${projectId}/views/${viewId}/sessions`);
    expect(fetcher.mock.calls[0]?.[1]).toEqual(expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ title: 'Session 2' }),
    }));
    expect(fetcher.mock.calls[1]?.[0]).toBe(`https://diagram.test/projects/${projectId}/sessions/${sessionId}/messages`);
  });

  it('posts attachment ids with a session message without changing the text-only payload', async () => {
    const message = {
      id: '99999999-9999-4999-8999-999999999999',
      sessionId,
      kind: 'HUMAN',
      authorUserId: '88888888-8888-4888-8888-888888888888',
      authorDisplayName: 'Morgan Reader',
      body: 'What is in this picture?',
      createdAt: '2026-02-01T16:00:00Z',
      replyTo: null,
      attachments: ['aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'],
    };
    const { fetcher, client } = clientWithBody(message);

    await client.postSessionMessage(projectId, sessionId, message.body, message.attachments);

    expect(fetcher).toHaveBeenCalledWith(
      `https://diagram.test/projects/${projectId}/sessions/${sessionId}/messages`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ body: message.body, attachmentIds: message.attachments }),
      }),
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

  it('reads the context meter and posts explicit compaction', async () => {
    const meter = {
      contextWindow: 4096,
      reservedForResponse: 512,
      promptBudget: 3584,
      instructions: 100,
      diagramData: 200,
      memories: 50,
      history: 300,
      images: 600,
      totalUsed: 1250,
      promptThreshold: 2868,
      compactionPending: false,
      percent: 34,
    };
    const { fetcher, client } = clientWithBody(meter);
    await client.getContextMeter(projectId, sessionId);
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({ compacted: true, reason: 'ok' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    expect(await client.compactSession(projectId, sessionId)).toEqual({ compacted: true, reason: 'ok' });

    expect(fetcher.mock.calls[0]?.[0]).toBe(`https://diagram.test/projects/${projectId}/sessions/${sessionId}/meter`);
    expect(fetcher.mock.calls[1]?.[0]).toBe(`https://diagram.test/projects/${projectId}/sessions/${sessionId}/compact`);
    expect(fetcher.mock.calls[1]?.[1]).toEqual(expect.objectContaining({ method: 'POST' }));
  });

  it('lists, extracts, and forgets diagram memories separately from session history', async () => {
    const memory = {
      memoryId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      kind: 'DECISION',
      status: 'ACTIVE',
      content: 'Keep the actor external to the system boundary.',
      sourceSessionId: sessionId,
      sourceMessageId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      replacedBy: null,
      createdAt: '2026-02-01T18:00:00Z',
    };
    const { fetcher, client } = clientWithBody([memory]);
    expect(await client.listMemories(projectId, viewId)).toEqual([memory]);
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({
      memoriesCreated: 1, conflictsMarked: 0, replacements: 0, consumed: true,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    await client.extractMemories(projectId, viewId);
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({ deletedMemories: 1 }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }));
    await client.forgetMemory(projectId, viewId, memory.memoryId);
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({ deletedMemories: 3 }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }));
    await client.forgetAllMemories(projectId, viewId);

    expect(fetcher.mock.calls.map(([url, init]) => [url, init?.method ?? 'GET'])).toEqual([
      [`https://diagram.test/projects/${projectId}/views/${viewId}/memories`, 'GET'],
      [`https://diagram.test/projects/${projectId}/views/${viewId}/memories/extract`, 'POST'],
      [`https://diagram.test/projects/${projectId}/views/${viewId}/memories/${memory.memoryId}`, 'DELETE'],
      [`https://diagram.test/projects/${projectId}/views/${viewId}/memories`, 'DELETE'],
    ]);
  });

  it('deletes only the selected session history and uploads then downloads image bytes', async () => {
    const { fetcher, client } = clientWithBody({ deletedMessages: 4 });
    expect(await client.deleteSessionHistory(projectId, sessionId)).toEqual({ deletedMessages: 4 });

    const attachment = {
      attachmentId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
      filename: 'diagram.png',
      sizeBytes: 12,
      contentType: 'image/png',
    };
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify(attachment), {
      status: 201, headers: { 'Content-Type': 'application/json' },
    }));
    const file = new Blob(['png bytes'], { type: 'image/png' }) as File;
    expect(await client.uploadAttachment(projectId, sessionId, file)).toEqual(attachment);
    const uploadInit = fetcher.mock.calls[1]?.[1];
    expect(fetcher.mock.calls[1]?.[0]).toBe(`https://diagram.test/projects/${projectId}/sessions/${sessionId}/attachments`);
    expect(uploadInit?.method).toBe('POST');
    expect(new Headers(uploadInit?.headers).has('Content-Type')).toBe(false);
    expect((uploadInit?.body as FormData).get('file')).toBeInstanceOf(Blob);

    const imageBytes = new Uint8Array([137, 80, 78, 71]);
    fetcher.mockResolvedValueOnce(new Response(imageBytes, {
      status: 200,
      headers: { 'Content-Type': 'image/png' },
    }));
    const downloaded = await client.getAttachmentBytes(projectId, sessionId, attachment.attachmentId);
    expect([...new Uint8Array(await downloaded.arrayBuffer())]).toEqual([...imageBytes]);
    expect(fetcher.mock.calls[2]?.[0]).toBe(
      `https://diagram.test/projects/${projectId}/sessions/${sessionId}/attachments/${attachment.attachmentId}`,
    );
  });
});
