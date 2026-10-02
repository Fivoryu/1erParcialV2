import { afterEach, describe, expect, it, vi } from 'vitest';
import { saveSession, SESSION_STORAGE_KEY } from '../api/session';
import { parseSseFrames, subscribeProjectEvents, type ProjectEventMessage } from './projectEvents';

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();

  get length(): number {
    return this.values.size;
  }

  clear(): void {
    this.values.clear();
  }

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  key(index: number): string | null {
    return [...this.values.keys()][index] ?? null;
  }

  removeItem(key: string): void {
    this.values.delete(key);
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }
}

afterEach(() => {
  vi.unstubAllGlobals();
});

function streamResponse(...chunks: string[]): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk));
      }
      controller.close();
    },
  });
  return new Response(body, { status: 200 });
}

async function settleMicrotasks(): Promise<void> {
  for (let index = 0; index < 24; index += 1) {
    await Promise.resolve();
  }
}

describe('project event transport', () => {
  it('parses multiple CRLF/LF frames, joins data lines, and preserves a split frame', () => {
    const firstChunk = parseSseFrames(
      'event: presence.updated\r\ndata: {"presence":\r\ndata: []}\r\n\r\nevent: operation.applied\ndata: {"revision":\n',
    );

    expect(firstChunk.events).toEqual([
      { type: 'presence.updated', data: '{"presence":\n[]}' },
    ]);
    expect(firstChunk.rest).toBe('event: operation.applied\ndata: {"revision":\n');

    const secondChunk = parseSseFrames(`${firstChunk.rest}data: 2}\n\n`);
    expect(secondChunk.events).toEqual([
      { type: 'operation.applied', data: '{"revision":\n2}' },
    ]);
    expect(secondChunk.rest).toBe('');
  });

  it('ignores heartbeat comments and malformed non-data lines without throwing', () => {
    expect(() => parseSseFrames(': heartbeat\n\nnot-a-field\n\n: another comment\r\n\r\n')).not.toThrow();
    expect(parseSseFrames(': heartbeat\n\nnot-a-field\n\n: another comment\r\n\r\n'))
      .toEqual({ events: [], rest: '' });
  });

  it('does not retry when the event stream rejects authentication', async () => {
    vi.stubGlobal('localStorage', new MemoryStorage());
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 401 }));
    const retries: Array<{ callback: () => void; delayMs: number }> = [];
    const unsubscribe = subscribeProjectEvents({
      projectId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      fetcher,
      onEvent: () => undefined,
      schedule: (callback, delayMs) => retries.push({ callback, delayMs }),
    });

    await settleMicrotasks();

    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(retries).toHaveLength(0);
    unsubscribe();
  });

  it('does not reconnect from a queued retry after authentication becomes terminal', async () => {
    vi.stubGlobal('localStorage', new MemoryStorage());
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
      .mockResolvedValueOnce(new Response(null, { status: 401 }));
    const retries: Array<{ callback: () => void; delayMs: number }> = [];
    const unsubscribe = subscribeProjectEvents({
      projectId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      fetcher,
      onEvent: () => undefined,
      schedule: (callback, delayMs) => retries.push({ callback, delayMs }),
    });

    await settleMicrotasks();
    expect(retries).toHaveLength(1);

    const staleRetry = retries[0]?.callback;
    staleRetry?.();
    await settleMicrotasks();
    expect(fetcher).toHaveBeenCalledTimes(2);

    // Replaying the callback queued before the 401 must not start another connection.
    staleRetry?.();
    await settleMicrotasks();

    expect(fetcher).toHaveBeenCalledTimes(2);
    unsubscribe();
  });

  it('retries a 5xx response with the capped-backoff schedule', async () => {
    vi.stubGlobal('localStorage', new MemoryStorage());
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
      .mockResolvedValueOnce(streamResponse());
    const retries: Array<{ callback: () => void; delayMs: number }> = [];
    const unsubscribe = subscribeProjectEvents({
      projectId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      fetcher,
      onEvent: () => undefined,
      schedule: (callback, delayMs) => retries.push({ callback, delayMs }),
    });

    await settleMicrotasks();
    expect(retries.map(({ delayMs }) => delayMs)).toEqual([250]);

    retries[0]?.callback();
    await settleMicrotasks();

    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(retries.map(({ delayMs }) => delayMs)).toEqual([250, 500]);
    unsubscribe();
  });

  it('parses JSON events, skips bad JSON, retries deterministically, and stops after unsubscribe', async () => {
    vi.stubGlobal('localStorage', new MemoryStorage());
    saveSession({
      token: 'opaque-session-token',
      userId: '11111111-1111-4111-8111-111111111111',
      username: 'diagram.user',
      displayName: 'Diagram User',
      expiresAt: '2026-10-01T12:00:00Z',
    });
    expect(globalThis.localStorage.getItem(SESSION_STORAGE_KEY)).not.toBeNull();

    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(streamResponse(
        'event: presence.updated\r\ndata: {"schemaVersion":"1.0","type":"presence.updated","payload":{"presence":',
        '[]}}\r\n\r\nevent: operation.applied\ndata: {"schemaVersion":"1.0","type":"operation.applied","payload":{"operationId":"op-1"}}\n\n',
        'event: invalid\ndata: {bad json}\n\n',
      ))
      .mockRejectedValueOnce(new Error('offline'));
    const retries: Array<{ callback: () => void; delayMs: number }> = [];
    const schedule = (callback: () => void, delayMs: number) => {
      retries.push({ callback, delayMs });
    };
    const events: ProjectEventMessage[] = [];
    const unsubscribe = subscribeProjectEvents({
      projectId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      baseUrl: 'https://diagram.test/',
      fetcher,
      onEvent: (event) => events.push(event),
      schedule,
    });

    await settleMicrotasks();

    expect(events).toEqual([
      {
        type: 'presence.updated',
        data: {
          schemaVersion: '1.0',
          type: 'presence.updated',
          payload: { presence: [] },
        },
      },
      {
        type: 'operation.applied',
        data: {
          schemaVersion: '1.0',
          type: 'operation.applied',
          payload: { operationId: 'op-1' },
        },
      },
    ]);
    expect(fetcher).toHaveBeenCalledWith(
      'https://diagram.test/projects/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/events',
      expect.objectContaining({
        method: 'GET',
        headers: expect.objectContaining({}),
      }),
    );
    const requestHeaders = new Headers(fetcher.mock.calls[0]?.[1]?.headers);
    expect(requestHeaders.get('Accept')).toBe('text/event-stream');
    expect(requestHeaders.get('Authorization')).toBe('Bearer opaque-session-token');
    expect(retries.map(({ delayMs }) => delayMs)).toEqual([250]);

    retries[0]?.callback();
    await settleMicrotasks();
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(retries.map(({ delayMs }) => delayMs)).toEqual([250, 500]);

    unsubscribe();
    retries[1]?.callback();
    await settleMicrotasks();
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(events).toHaveLength(2);
  });
});
