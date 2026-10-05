import { ApiTransportError } from './client';
import { clearSession, getAuthorizationHeaderValue } from './session';
import type { Uuid } from './types';

/** IA4-04 undo/redo endpoints. The shape mirrors backend com.diagramador.undo.UndoResult:
 * ACCEPTED carries the executor's operationId/resultRevision; REJECTED carries a declared
 * code (NOTHING_TO_UNDO, NOTHING_TO_REDO, XMI_IMPORT_NOT_UNDOABLE, CASCADE_REQUIRED, ...). */
export interface UndoRedoResult {
  status: 'ACCEPTED' | 'REJECTED';
  code: string | null;
  message: string | null;
  operationId: Uuid | null;
  resultRevision: number | null;
}

export interface UndoRedoApi {
  undo(projectId: Uuid): Promise<UndoRedoResult>;
  redo(projectId: Uuid): Promise<UndoRedoResult>;
}

interface ApiErrorBody {
  code?: unknown;
  message?: unknown;
  details?: unknown;
  operationId?: unknown;
}

function transportErrorFromBody(body: unknown, status: number): ApiTransportError {
  if (typeof body === 'object' && body !== null) {
    const candidate = body as ApiErrorBody;
    if (typeof candidate.code === 'string' && typeof candidate.message === 'string') {
      return new ApiTransportError(
        candidate.code,
        candidate.message,
        Array.isArray(candidate.details) ? candidate.details as Array<Record<string, unknown>> : [],
        typeof candidate.operationId === 'string' ? candidate.operationId : null,
        status,
      );
    }
  }
  return new ApiTransportError('HTTP_ERROR', `The server returned HTTP ${status}.`, [], null, status);
}

function isUndoRedoResult(value: unknown): value is UndoRedoResult {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const status = (value as Partial<UndoRedoResult>).status;
  return status === 'ACCEPTED' || status === 'REJECTED';
}

function normalizeResult(body: UndoRedoResult): UndoRedoResult {
  return {
    status: body.status,
    code: typeof body.code === 'string' ? body.code : null,
    message: typeof body.message === 'string' ? body.message : null,
    operationId: typeof body.operationId === 'string' ? body.operationId : null,
    resultRevision: typeof body.resultRevision === 'number' ? body.resultRevision : null,
  };
}

export function createUndoRedoApi(
  baseUrl: string = import.meta.env.VITE_API_BASE_URL ?? '',
  fetcher: typeof fetch = fetch,
): UndoRedoApi {
  const base = baseUrl.replace(/\/+$/, '');

  async function request(projectId: Uuid, action: 'undo' | 'redo'): Promise<UndoRedoResult> {
    const path = `/projects/${encodeURIComponent(projectId)}/${action}`;
    const headers = new Headers();
    const authorization = getAuthorizationHeaderValue();
    if (authorization) {
      headers.set('Authorization', authorization);
    }

    let response: Response;
    try {
      response = await fetcher(`${base}${path}`, { method: 'POST', headers });
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : 'The server could not be reached.';
      throw new ApiTransportError('NETWORK_ERROR', message, [], null, null, { cause });
    }

    if (response.status === 401) {
      clearSession();
    }

    const body: unknown = await response.json().catch(() => null);
    if (!response.ok) {
      throw transportErrorFromBody(body, response.status);
    }
    if (!isUndoRedoResult(body)) {
      throw new ApiTransportError(
        'UNDO_REDO_MALFORMED',
        'The server returned an undo/redo result that could not be read.',
        [],
        null,
        response.status,
      );
    }
    return normalizeResult(body);
  }

  return {
    undo(projectId) {
      return request(projectId, 'undo');
    },
    redo(projectId) {
      return request(projectId, 'redo');
    },
  };
}
