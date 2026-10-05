import type { Uuid } from './types';
import { ApiTransportError } from './client';
import { clearSession, getAuthorizationHeaderValue } from './session';

export interface AssistantDiagnosticResource {
  code: string;
  message: string;
}

export interface AssistantPlanOperationResource {
  type: string;
  targetId: Uuid | null;
  args: Record<string, unknown>;
}

export interface AssistantPlanResource {
  intent: 'PLAN' | 'EXPLAIN' | 'CLARIFY' | 'REJECT';
  operations: AssistantPlanOperationResource[];
  question: string | null;
  explanation: string | null;
  diagnostics: AssistantDiagnosticResource[];
}

export interface AssistantApplyOperation {
  type: string;
  targetId: Uuid | null;
  args: Record<string, unknown>;
}

export interface AssistantApplyStepResource {
  index: number;
  type: string | null;
  targetId: Uuid | null;
  status: 'ACCEPTED' | 'REJECTED' | 'SKIPPED';
  operationId: Uuid | null;
  resultRevision: number | null;
  diagnostics: AssistantDiagnosticResource[];
}

export interface AssistantApplyResource {
  steps: AssistantApplyStepResource[];
  finalRevision: number;
}

export interface AssistantApi {
  requestPlan(projectId: Uuid, utterance: string, baseRevision: number): Promise<AssistantPlanResource>;
  applyPlan(
    projectId: Uuid,
    operations: AssistantApplyOperation[],
    baseRevision: number,
  ): Promise<AssistantApplyResource>;
}

function isApiErrorResource(value: unknown): value is {
  code: string;
  message: string;
  details: Array<Record<string, unknown>>;
  operationId: Uuid | null;
} {
  if (typeof value !== 'object' || value === null) {
    return false;
  }

  const candidate = value as { code?: unknown; message?: unknown; details?: unknown; operationId?: unknown };
  return typeof candidate.code === 'string'
    && typeof candidate.message === 'string'
    && Array.isArray(candidate.details)
    && (candidate.operationId === null || typeof candidate.operationId === 'string');
}

function transportErrorFromBody(body: unknown, status: number): ApiTransportError {
  if (isApiErrorResource(body)) {
    return new ApiTransportError(body.code, body.message, body.details, body.operationId, status);
  }

  return new ApiTransportError('HTTP_ERROR', `The server returned HTTP ${status}.`, [], null, status);
}

export function createAssistantApi(
  baseUrl: string = import.meta.env.VITE_API_BASE_URL ?? '',
  fetcher: typeof fetch = fetch,
): AssistantApi {
  const base = baseUrl.replace(/\/+$/, '');

  function authorizedInit(init?: RequestInit): RequestInit {
    const headers = new Headers(init?.headers);
    const authorization = getAuthorizationHeaderValue();
    if (authorization) {
      headers.set('Authorization', authorization);
    }
    return { ...init, headers };
  }

  async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
    let response: Response;
    try {
      response = await fetcher(`${base}${path}`, authorizedInit(init));
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : 'The server could not be reached.';
      throw new ApiTransportError('NETWORK_ERROR', message, [], null, null, { cause });
    }

    if (response.status === 401) {
      clearSession();
    }

    let body: unknown = null;
    try {
      body = await response.json();
    } catch {
      body = null;
    }

    if (!response.ok) {
      throw transportErrorFromBody(body, response.status);
    }

    return body as T;
  }

  function postJson<T>(path: string, payload: unknown): Promise<T> {
    return requestJson<T>(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
  }

  return {
    requestPlan(projectId, utterance, baseRevision) {
      return postJson<AssistantPlanResource>(
        `/projects/${encodeURIComponent(projectId)}/assistant/plan`,
        { utterance, baseRevision },
      );
    },
    applyPlan(projectId, operations, baseRevision) {
      return postJson<AssistantApplyResource>(
        `/projects/${encodeURIComponent(projectId)}/assistant/apply`,
        { operations, baseRevision },
      );
    },
  };
}
