import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { buildOperationEnvelope, type UuidFactory } from './envelope';
import { ApiTransportError, createApiClient } from './client';
import { clearSession, loadSession, saveSession, SESSION_STORAGE_KEY } from './session';
import type {
  ChatMessageResource,
  ModelResource,
  OperationEnvelope,
  UserResource,
  XmiExportResource,
} from './types';
import recordedModelFixture from './fixtures/model-response.json';

// Captured from GET /projects/{id}/model during the Increment 1 acceptance run. The `versionId`
// field was added to this recording when T9 made the frozen version identity discoverable by clients;
// no other field was touched.
const recordedModel: ModelResource = recordedModelFixture;
const projectId = '22222222-2222-4222-8222-222222222222';
const modelId = '33333333-3333-4333-8333-333333333333';

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

function sequenceUuidFactory(...values: string[]): UuidFactory {
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

function classifierCreateEnvelope(uuidFactory: UuidFactory): OperationEnvelope {
  return buildOperationEnvelope({
    type: 'classifier.create',
    projectId,
    modelId,
    baseRevision: 4,
    payload: {
      id: 'must-be-replaced-by-client-id',
      name: 'Customer',
      kind: 'CLASS',
      namespaceId: null,
    },
  }, uuidFactory);
}

describe('operation envelope builder', () => {
  it('builds every required field and creates distinct client-owned ids on each call', () => {
    const uuidFactory = sequenceUuidFactory(
      '00000000-0000-4000-8000-000000000001',
      '00000000-0000-4000-8000-000000000002',
      '00000000-0000-4000-8000-000000000003',
      '00000000-0000-4000-8000-000000000004',
      '00000000-0000-4000-8000-000000000005',
      '00000000-0000-4000-8000-000000000006',
    );

    const first = classifierCreateEnvelope(uuidFactory);
    const second = classifierCreateEnvelope(uuidFactory);

    expect(first).toEqual({
      operationId: '00000000-0000-4000-8000-000000000001',
      type: 'classifier.create',
      author: {
        userId: '00000000-0000-0000-0000-000000000001',
        externalSubject: 'seed-owner-local',
      },
      projectId,
      modelId,
      baseRevision: 4,
      targets: [],
      preconditions: [],
      payload: {
        id: '00000000-0000-4000-8000-000000000002',
        name: 'Customer',
        kind: 'CLASS',
        namespaceId: null,
      },
      origin: 'MANUAL',
      causalParentOperationId: null,
      idempotencyKey: '00000000-0000-4000-8000-000000000003',
    });

    const generatedIds = [
      first.operationId,
      first.payload.id,
      first.idempotencyKey,
      second.operationId,
      second.payload.id,
      second.idempotencyKey,
    ];
    expect(new Set(generatedIds).size).toBe(generatedIds.length);
    expect(second.operationId).not.toBe(first.operationId);
    expect(second.payload.id).not.toBe(first.payload.id);
  });
});

describe('typed API client', () => {
  it('surfaces an HTTP 200 REJECTED command as a rejection with its ErrorCode', async () => {
    const resultBody = {
      operationId: '44444444-4444-4444-8444-444444444444',
      status: 'REJECTED',
      resultRevision: null,
      diagnostics: [{
        code: 'PRECONDITION_FAILED',
        message: 'Classifier name is already in use.',
        scope: null,
        id: null,
        reason: null,
      }],
      authorizationClassification: null,
    };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(resultBody), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    const client = createApiClient('https://diagram.test', fetcher);
    const envelope = classifierCreateEnvelope(sequenceUuidFactory(
      '55555555-5555-4555-8555-555555555551',
      '55555555-5555-4555-8555-555555555552',
      '55555555-5555-4555-8555-555555555553',
    ));

    const outcome = await client.executeCommand(projectId, envelope);

    expect(outcome.kind).toBe('rejected');
    if (outcome.kind !== 'rejected') {
      throw new Error('Expected the command to be rejected.');
    }
    expect(outcome.result.status).toBe('REJECTED');
    expect(outcome.diagnostics[0]?.code).toBe('PRECONDITION_FAILED');
    expect(fetcher).toHaveBeenCalledWith(
      `https://diagram.test/projects/${projectId}/commands`,
      expect.objectContaining({ method: 'POST' }),
    );
  });

  it('surfaces the section 3.2 error body as a distinct transport failure', async () => {
    const errorBody = {
      code: 'PRECONDITION_FAILED',
      message: 'The model has an unresolved name collision.',
      details: [{ scope: 'name_collision', id: '66666666-6666-4666-8666-666666666666', reason: 'name.duplicate' }],
      operationId: '77777777-7777-4777-8777-777777777777',
    };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(errorBody), {
      status: 409,
      headers: { 'Content-Type': 'application/json' },
    }));
    const client = createApiClient('https://diagram.test', fetcher);
    const request = client.getProject(projectId);

    await expect(request).rejects.toBeInstanceOf(ApiTransportError);
    await expect(request).rejects.toMatchObject({
      kind: 'transport',
      code: 'PRECONDITION_FAILED',
      message: 'The model has an unresolved name collision.',
      details: errorBody.details,
      operationId: errorBody.operationId,
      status: 409,
    });
  });

  it('keeps the recorded live model response aligned with the resource types', () => {
    expect(recordedModel.projectId).toBe('b475cd3a-a203-4230-947a-1d42cd878ea2');
    expect(recordedModel.versionId).toBe('9f2b7c41-6d0a-4c2e-9d3b-4a1f5e7c8b90');
    expect(recordedModel.views[0]?.is_default).toBe(true);
    expect(recordedModel.views[0]?.element_geometry).toHaveLength(2);
    expect(recordedModel.views[0]?.connector_path).toHaveLength(2);
    expect(recordedModel.classifiers[0]?.attributes[0]?.type).toBe('String');
    expect(recordedModel.classifiers[0]?.operations[0]?.parameters).toEqual([]);
    expect(recordedModel.relationships[0]?.ends[1]?.aggregationKind).toBe('NONE');
  });
});

describe('project member and invitation API client', () => {
  it('calls all member and invitation routes with contract methods, bodies, and the session bearer', async () => {
    const projectId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
    const userId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
    const invitationId = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (input) => (
      String(input).endsWith('/presence')
        ? new Response(null, { status: 204 })
        : new Response('[]', { status: 200 })
    ));
    const client = createApiClient('https://diagram.test', fetcher);
    saveSession({
      token: 'opaque-session-token',
      userId,
      username: 'diagram.user',
      displayName: 'Diagram User',
      expiresAt: '2999-01-01T00:00:00Z',
    });

    await client.listProjectMembers(projectId);
    await client.listProjectInvitations(projectId);
    await client.createProjectInvitation(projectId, { username: 'new.member', role: 'EDITOR' });
    await client.acceptProjectInvitation(projectId, invitationId);
    await client.revokeProjectInvitation(projectId, invitationId);
    await client.updateProjectMemberRole(projectId, userId, { role: 'VIEWER' });
    await client.removeProjectMember(projectId, userId);
    await client.listMyInvitations();
    const touchResult = await client.reportProjectPresenceTouch(projectId, 'dddddddd-dddd-4ddd-8ddd-dddddddddddd');
    await client.reportProjectPresenceTouch(projectId, null);
    const cursorResult = await client.reportProjectCursorPosition(projectId, 123.5, 456.25);

    expect(touchResult).toBeUndefined();
    expect(cursorResult).toBeUndefined();
    const calls = fetcher.mock.calls;
    expect(calls.map(([input]) => String(input))).toEqual([
      `https://diagram.test/projects/${projectId}/members`,
      `https://diagram.test/projects/${projectId}/invitations`,
      `https://diagram.test/projects/${projectId}/invitations`,
      `https://diagram.test/projects/${projectId}/invitations/${invitationId}/accept`,
      `https://diagram.test/projects/${projectId}/invitations/${invitationId}/revoke`,
      `https://diagram.test/projects/${projectId}/members/${userId}`,
      `https://diagram.test/projects/${projectId}/members/${userId}`,
      'https://diagram.test/invitations',
      `https://diagram.test/projects/${projectId}/presence`,
      `https://diagram.test/projects/${projectId}/presence`,
      `https://diagram.test/projects/${projectId}/presence`,
    ]);
    expect(calls.map(([, init]) => init?.method)).toEqual([
      undefined,
      undefined,
      'POST',
      'POST',
      'POST',
      'PATCH',
      'DELETE',
      undefined,
      'POST',
      'POST',
      'POST',
    ]);
    expect(JSON.parse(String(calls[2]?.[1]?.body))).toEqual({ username: 'new.member', role: 'EDITOR' });
    expect(calls[3]?.[1]?.body).toBeUndefined();
    expect(calls[4]?.[1]?.body).toBeUndefined();
    expect(JSON.parse(String(calls[5]?.[1]?.body))).toEqual({ role: 'VIEWER' });
    expect(calls[6]?.[1]?.body).toBeUndefined();
    expect(JSON.parse(String(calls[8]?.[1]?.body))).toEqual({
      targetElementId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
    });
    expect(JSON.parse(String(calls[9]?.[1]?.body))).toEqual({ targetElementId: null });
    expect(JSON.parse(String(calls[10]?.[1]?.body))).toEqual({ cursorX: 123.5, cursorY: 456.25 });
    expect(new Headers(calls[8]?.[1]?.headers).get('Content-Type')).toBe('application/json');
    expect(new Headers(calls[9]?.[1]?.headers).get('Content-Type')).toBe('application/json');
    expect(new Headers(calls[10]?.[1]?.headers).get('Content-Type')).toBe('application/json');
    for (const [, init] of calls) {
      expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer opaque-session-token');
    }
  });

  it('requires the presence endpoint to return 204 No Content', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 200 }));
    const client = createApiClient('https://diagram.test', fetcher);

    await expect(client.reportProjectPresenceTouch(projectId, null)).rejects.toMatchObject({
      kind: 'transport',
      code: 'HTTP_ERROR',
      status: 200,
    });
  });
});

describe('project chat API client', () => {
  it('lists and posts messages on the project chat routes with the server resource shape', async () => {
    const authorUserId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
    const message: ChatMessageResource = {
      id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      authorUserId,
      authorDisplayName: 'Diagram User',
      body: 'Hello, team.',
      createdAt: '2026-02-01T12:34:56Z',
    };
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify([message]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }))
      // POST returns the created resource with HTTP 201.
      .mockResolvedValueOnce(new Response(JSON.stringify(message), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      }));
    const client = createApiClient('https://diagram.test', fetcher);
    saveSession({
      token: 'opaque-session-token',
      userId: authorUserId,
      username: 'diagram.user',
      displayName: 'Diagram User',
      expiresAt: '2999-01-01T00:00:00Z',
    });

    expect(await client.listProjectMessages(projectId)).toEqual([message]);
    expect(await client.postProjectMessage(projectId, message.body)).toEqual(message);

    const calls = fetcher.mock.calls;
    expect(calls.map(([input]) => String(input))).toEqual([
      `https://diagram.test/projects/${projectId}/messages`,
      `https://diagram.test/projects/${projectId}/messages`,
    ]);
    expect(calls.map(([, init]) => init?.method)).toEqual([undefined, 'POST']);
    expect(JSON.parse(String(calls[1]?.[1]?.body))).toEqual({ body: 'Hello, team.' });
    expect(new Headers(calls[1]?.[1]?.headers).get('Content-Type')).toBe('application/json');
    for (const [, init] of calls) {
      expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer opaque-session-token');
    }
  });
});

const liveBaseUrl = import.meta.env.VITE_API_BASE_URL;
describe.skipIf(!liveBaseUrl)('live backend client contract', () => {
  it('creates, reads, and commands a project through the typed client', async () => {
    const commandStatuses: number[] = [];
    const liveFetcher: typeof fetch = async (input, init) => {
      const response = await fetch(input, init);
      if (String(input).endsWith('/commands')) {
        commandStatuses.push(response.status);
      }
      return response;
    };
    const client = createApiClient(liveBaseUrl, liveFetcher);
    const project = await client.createProject({
      name: `T7 browser client verification ${new Date().toISOString()}`,
      description: 'Created by the opt-in web client integration test.',
    });

    expect(project.name).toContain('T7 browser client verification');
    expect((await client.listProjects()).some((item) => item.id === project.id)).toBe(true);
    expect(await client.getProject(project.id)).toEqual(project);

    const model = await client.getModel(project.id);
    expect(model.projectId).toBe(project.id);
    expect(model.views.some((view) => view.is_default)).toBe(true);

    const classifierCreate = buildOperationEnvelope({
      type: 'classifier.create',
      projectId: project.id,
      modelId: model.id,
      baseRevision: model.revision,
      payload: { name: 'Order', kind: 'CLASS', namespaceId: null },
    });
    expect(classifierCreate.operationId).not.toBe(classifierCreate.payload.id);
    if (import.meta.env.VITE_PRINT_SAMPLE_ENVELOPE === 'true') {
      console.log(JSON.stringify(classifierCreate, null, 2));
    }

    const accepted = await client.executeCommand(project.id, classifierCreate);
    expect(accepted.kind).toBe('accepted');
    const updatedModel = await client.getModel(project.id);
    expect(updatedModel.classifiers[0]?.id).toBe(classifierCreate.payload.id);

    const duplicate = await client.executeCommand(project.id, buildOperationEnvelope({
      type: 'classifier.create',
      projectId: project.id,
      modelId: updatedModel.id,
      baseRevision: updatedModel.revision,
      payload: { name: 'Order', kind: 'CLASS', namespaceId: null },
    }));
    expect(duplicate.kind).toBe('rejected');
    if (duplicate.kind !== 'rejected') {
      throw new Error('The duplicate classifier should be rejected by the live backend.');
    }
    expect(duplicate.diagnostics[0]?.code).toBe('PRECONDITION_FAILED');
    expect(commandStatuses).toEqual([200, 200]);
  });
});

describe('session-aware API client', () => {
  const user: UserResource = {
    id: '88888888-8888-4888-8888-888888888888',
    username: 'diagram.user',
    displayName: 'Diagram User',
    email: 'diagram@example.test',
  };
  const loginResponse = {
    token: 'opaque-session-token',
    expiresAt: '2999-01-01T00:00:00Z',
    user,
  };

  it('attaches the bearer when a session exists and omits it otherwise', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => new Response('[]', { status: 200 }));
    const client = createApiClient('https://diagram.test', fetcher);
    saveSession({
      token: loginResponse.token,
      userId: user.id,
      username: user.username,
      displayName: user.displayName,
      expiresAt: loginResponse.expiresAt,
    });

    await client.listProjects();
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Authorization')).toBe('Bearer opaque-session-token');

    clearSession();
    await client.listProjects();
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get('Authorization')).toBeNull();
  });

  it('returns a login token without storing a session itself', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(loginResponse), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    const client = createApiClient('https://diagram.test', fetcher);

    const result = await client.login({ username: user.username, password: 'correct-horse' });

    expect(result).toEqual(loginResponse);
    expect(globalThis.localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    expect(fetcher).toHaveBeenCalledWith('https://diagram.test/auth/login', expect.any(Object));
  });

  it('clears the session after a 401 from a protected endpoint', async () => {
    saveSession({
      token: loginResponse.token,
      userId: user.id,
      username: user.username,
      displayName: user.displayName,
      expiresAt: loginResponse.expiresAt,
    });
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({
      code: 'UNAUTHENTICATED',
      message: 'A valid session is required.',
      details: [],
      operationId: null,
    }), { status: 401, headers: { 'Content-Type': 'application/json' } }));
    const client = createApiClient('https://diagram.test', fetcher);

    await expect(client.me()).rejects.toMatchObject({ code: 'UNAUTHENTICATED', status: 401 });

    expect(loadSession()).toBeNull();
  });

  it('calls each auth endpoint with its contract path and request body', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify(user), { status: 201 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(loginResponse), { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(user), { status: 200 }));
    const client = createApiClient('https://diagram.test', fetcher);
    saveSession({
      token: loginResponse.token,
      userId: user.id,
      username: user.username,
      displayName: user.displayName,
      expiresAt: loginResponse.expiresAt,
    });
    const registerRequest = {
      username: user.username,
      password: 'correct-horse',
      displayName: user.displayName,
      email: 'diagram@example.test',
    };
    const loginRequest = { username: user.username, password: 'correct-horse' };

    await client.register(registerRequest);
    await client.login(loginRequest);
    await client.logout();
    await client.me();

    expect(fetcher.mock.calls.map(([input]) => String(input))).toEqual([
      'https://diagram.test/auth/register',
      'https://diagram.test/auth/login',
      'https://diagram.test/auth/logout',
      'https://diagram.test/auth/me',
    ]);
    expect(JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body))).toEqual(registerRequest);
    expect(JSON.parse(String(fetcher.mock.calls[1]?.[1]?.body))).toEqual(loginRequest);
    expect(fetcher.mock.calls[2]?.[1]?.method).toBe('POST');
    expect(fetcher.mock.calls[2]?.[1]?.body).toBeUndefined();
    expect(fetcher.mock.calls[3]?.[1]?.body).toBeUndefined();
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Authorization')).toBeNull();
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get('Authorization')).toBeNull();
    expect(new Headers(fetcher.mock.calls[2]?.[1]?.headers).get('Authorization')).toBe('Bearer opaque-session-token');
    expect(new Headers(fetcher.mock.calls[3]?.[1]?.headers).get('Authorization')).toBe('Bearer opaque-session-token');
  });

  it('does not clear an existing session for login or registration 401 responses', async () => {
    saveSession({
      token: loginResponse.token,
      userId: user.id,
      username: user.username,
      displayName: user.displayName,
      expiresAt: loginResponse.expiresAt,
    });
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({
      code: 'INVALID_CREDENTIALS',
      message: 'The username or password is incorrect.',
      details: [],
      operationId: null,
    }), { status: 401, headers: { 'Content-Type': 'application/json' } }));
    const client = createApiClient('https://diagram.test', fetcher);

    await expect(client.login({ username: user.username, password: 'wrong-password' })).rejects.toBeInstanceOf(ApiTransportError);
    await expect(client.register({
      username: user.username,
      password: 'correct-horse',
      displayName: user.displayName,
    })).rejects.toBeInstanceOf(ApiTransportError);

    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Authorization')).toBeNull();
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get('Authorization')).toBeNull();
    expect(loadSession()).not.toBeNull();
  });
});

describe('editing lease API client', () => {
  it('calls each editing-lease route with its contract method', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => new Response(
      JSON.stringify({ lease: null }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ));
    const client = createApiClient('https://diagram.test', fetcher);

    await client.getEditingLease(projectId);
    await client.acquireEditingLease(projectId);
    await client.renewEditingLease(projectId);
    await client.reclaimEditingLease(projectId);
    await client.releaseEditingLease(projectId);

    expect(fetcher.mock.calls.map(([input]) => String(input))).toEqual([
      `https://diagram.test/projects/${projectId}/editing-lease`,
      `https://diagram.test/projects/${projectId}/editing-lease`,
      `https://diagram.test/projects/${projectId}/editing-lease/renew`,
      `https://diagram.test/projects/${projectId}/editing-lease/reclaim`,
      `https://diagram.test/projects/${projectId}/editing-lease`,
    ]);
    expect(fetcher.mock.calls.map(([, init]) => init?.method)).toEqual([
      undefined,
      'POST',
      'POST',
      'POST',
      'DELETE',
    ]);
  });
});

describe('XMI export API client', () => {
  it('posts without a body to the export route and returns the parsed resource', async () => {
    const resource: XmiExportResource = {
      fileBase64: 'PD94bWwgdmVyc2lvbj0iMS4wIj8+',
      checksum: 'a'.repeat(64),
      exchangeId: '55555555-5555-4555-8555-555555555551',
      losses: [{ name: 'connector_path_not_exported', count: 2 }],
    };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(resource), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    const client = createApiClient('https://diagram.test', fetcher);
    saveSession({
      token: 'opaque-session-token',
      userId: '00000000-0000-0000-0000-000000000001',
      username: 'diagram.user',
      displayName: 'Diagram User',
      expiresAt: '2999-01-01T00:00:00Z',
    });

    expect(await client.exportXmi(projectId)).toEqual(resource);

    expect(fetcher).toHaveBeenCalledOnce();
    const [input, init] = fetcher.mock.calls[0] ?? [];
    expect(String(input)).toBe(`https://diagram.test/projects/${projectId}/xmi/export`);
    expect(init?.method).toBe('POST');
    // The backend declares no @RequestBody, so the request carries no body at all.
    expect(init?.body).toBeUndefined();
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer opaque-session-token');
  });

  it('surfaces a server rejection (such as an editing-lease conflict) as a transport error', async () => {
    const errorBody = {
      code: 'PRECONDITION_FAILED',
      message: 'Another editor holds the editing lease for this project.',
      details: [],
      operationId: null,
    };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(errorBody), {
      status: 409,
      headers: { 'Content-Type': 'application/json' },
    }));
    const client = createApiClient('https://diagram.test', fetcher);

    await expect(client.exportXmi(projectId)).rejects.toMatchObject({
      kind: 'transport',
      code: 'PRECONDITION_FAILED',
      message: 'Another editor holds the editing lease for this project.',
      status: 409,
    });
  });
});
