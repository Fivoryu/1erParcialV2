import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiTransportError } from './client';
import { createAssistantApi } from './assistant';
import { clearSession, saveSession } from './session';

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

const projectId = '22222222-2222-4222-8222-222222222222';

const planResponse = {
  intent: 'PLAN',
  operations: [
    {
      type: 'classifier.create',
      targetId: null,
      args: { name: 'Order' },
    },
  ],
  question: null,
  explanation: null,
  diagnostics: [],
};

const applyResponse = {
  steps: [
    {
      index: 0,
      type: 'classifier.create',
      targetId: null,
      status: 'ACCEPTED',
      operationId: '33333333-3333-4333-8333-333333333333',
      resultRevision: 8,
      diagnostics: [],
    },
  ],
  finalRevision: 8,
};

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

afterEach(() => {
  clearSession();
});

describe('assistant API client', () => {
  it('requests a plan with the utterance and base revision', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(planResponse));
    const client = createAssistantApi('https://diagram.test', fetcher);

    await expect(client.requestPlan(projectId, 'Creá una clase Order', 7)).resolves.toEqual(planResponse);

    expect(fetcher).toHaveBeenCalledWith(
      `https://diagram.test/projects/${projectId}/assistant/plan`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ utterance: 'Creá una clase Order', baseRevision: 7 }),
      }),
    );
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Content-Type')).toBe('application/json');
  });

  it('applies plan operations with the base revision', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(applyResponse));
    const client = createAssistantApi('https://diagram.test', fetcher);
    const operations = [{ type: 'classifier.create', targetId: null, args: { name: 'Order' } }];

    await expect(client.applyPlan(projectId, operations, 7)).resolves.toEqual(applyResponse);

    expect(fetcher).toHaveBeenCalledWith(
      `https://diagram.test/projects/${projectId}/assistant/apply`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ operations, baseRevision: 7 }),
      }),
    );
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Content-Type')).toBe('application/json');
  });

  it('sends the session bearer token when one is stored and omits it otherwise', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(applyResponse));
    const client = createAssistantApi('https://diagram.test', fetcher);

    await client.applyPlan(projectId, [], 7);
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Authorization')).toBeNull();

    saveSession({
      token: 'assistant-token',
      userId: '44444444-4444-4444-8444-444444444444',
      username: 'owner',
      displayName: 'Owner',
      expiresAt: '2999-01-01T00:00:00Z',
    });

    await client.applyPlan(projectId, [], 7);
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get('Authorization')).toBe('Bearer assistant-token');
  });

  it('maps provider-disabled HTTP 503 responses to a transport error', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse({ code: 'PROVIDER_DISABLED', message: 'disabled', details: [], operationId: null }, 503),
    );
    const client = createAssistantApi('https://diagram.test', fetcher);

    await expect(client.requestPlan(projectId, 'hola', 7)).rejects.toMatchObject({
      kind: 'transport',
      code: 'PROVIDER_DISABLED',
      status: 503,
    });
  });

  it('maps stale-apply HTTP 409 responses to a transport error with details', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse(
        { code: 'REVIEW_REQUIRED', message: 'stale', details: [{ revision: 8 }], operationId: null },
        409,
      ),
    );
    const client = createAssistantApi('https://diagram.test', fetcher);

    const error = await client.applyPlan(projectId, [], 7).catch((cause: unknown) => cause);

    expect(error).toBeInstanceOf(ApiTransportError);
    expect(error).toMatchObject({
      code: 'REVIEW_REQUIRED',
      status: 409,
      details: [{ revision: 8 }],
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});
