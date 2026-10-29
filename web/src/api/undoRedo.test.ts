import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiTransportError } from './client';
import { createUndoRedoApi, type UndoRedoResult } from './undoRedo';
import { saveSession, SESSION_STORAGE_KEY } from './session';

const projectId = '22222222-2222-4222-8222-222222222222';

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

beforeEach(() => {
  vi.stubGlobal('localStorage', new MemoryStorage());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function seedSession(): void {
  saveSession({
    token: 'opaque-session-token',
    userId: '11111111-1111-4111-8111-111111111111',
    username: 'casey',
    displayName: 'Casey',
    expiresAt: '2099-01-01T00:00:00Z',
  });
}

// Mirrors backend com.diagramador.undo.UndoResult as serialized by the controller.
const accepted: UndoRedoResult = {
  status: 'ACCEPTED',
  code: null,
  message: null,
  operationId: '33333333-3333-4333-8333-333333333333',
  resultRevision: 9,
};

describe('undo/redo API client', () => {
  it('undo POSTs to the project undo endpoint with the session bearer and mirrors the accepted result', async () => {
    seedSession();
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(accepted));
    const api = createUndoRedoApi('https://diagram.test', fetcher);

    await expect(api.undo(projectId)).resolves.toEqual(accepted);

    expect(fetcher).toHaveBeenCalledWith(
      `https://diagram.test/projects/${projectId}/undo`,
      expect.objectContaining({ method: 'POST' }),
    );
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Authorization'))
      .toBe('Bearer opaque-session-token');
  });

  it('redo POSTs to the project redo endpoint without a body and mirrors a NOTHING_TO_REDO rejection', async () => {
    const rejection: UndoRedoResult = {
      status: 'REJECTED',
      code: 'NOTHING_TO_REDO',
      message: 'No hay acciones para rehacer.',
      operationId: null,
      resultRevision: null,
    };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(rejection));
    const api = createUndoRedoApi('https://diagram.test', fetcher);

    await expect(api.redo(projectId)).resolves.toEqual(rejection);

    expect(fetcher).toHaveBeenCalledWith(
      `https://diagram.test/projects/${projectId}/redo`,
      expect.objectContaining({ method: 'POST' }),
    );
    expect(fetcher.mock.calls[0]?.[1]?.body).toBeUndefined();
  });

  it('maps an API error body to an ApiTransportError carrying the server code and status', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({
      code: 'FORBIDDEN',
      message: 'Not a member.',
      details: [],
      operationId: null,
    }, 403));
    const api = createUndoRedoApi('https://diagram.test', fetcher);

    await expect(api.undo(projectId)).rejects.toMatchObject({
      kind: 'transport',
      code: 'FORBIDDEN',
      message: 'No tienes permiso para realizar esta acción.',
      serverMessage: 'Not a member.',
      status: 403,
    });
  });

  it('falls back to HTTP_ERROR when the error body is not an API error resource', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response('gateway exploded', { status: 502 }));
    const api = createUndoRedoApi('https://diagram.test', fetcher);

    await expect(api.redo(projectId)).rejects.toMatchObject({
      kind: 'transport',
      code: 'HTTP_ERROR',
      status: 502,
    });
  });

  it('clears the session when the server answers 401', async () => {
    seedSession();
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({
      code: 'UNAUTHORIZED',
      message: 'The session expired.',
      details: [],
      operationId: null,
    }, 401));
    const api = createUndoRedoApi('https://diagram.test', fetcher);

    await expect(api.undo(projectId)).rejects.toBeInstanceOf(ApiTransportError);
    expect(globalThis.localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });
});
