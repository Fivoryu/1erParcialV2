import type {
  ApiErrorResource,
  CommandOutcome,
  CommandResult,
  CreateGenerationRequest,
  CreateGenerationTargetRequest,
  CreateInvitationRequest,
  CreateProjectRequest,
  DeploymentResource,
  GenerationArchiveDownload,
  GenerationProfileResource,
  GenerationRunResource,
  GenerationTargetResource,
  InvitationResource,
  MemberResource,
  ModelResource,
  OperationEnvelope,
  OperationOutcomeResource,
  ProjectResource,
  RegisterRequest,
  LoginRequest,
  LoginResponse,
  UpdateMemberRoleRequest,
  UserResource,
  Uuid,
} from './types';
import { clearSession, getAuthorizationHeaderValue } from './session';

export class ApiTransportError extends Error {
  readonly kind = 'transport';

  constructor(
    readonly code: string,
    message: string,
    readonly details: Array<Record<string, unknown>>,
    readonly operationId: Uuid | null,
    readonly status: number | null,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = 'ApiTransportError';
  }
}

function isApiErrorResource(value: unknown): value is ApiErrorResource {
  if (typeof value !== 'object' || value === null) {
    return false;
  }

  const candidate = value as Partial<ApiErrorResource>;
  return typeof candidate.code === 'string'
    && typeof candidate.message === 'string'
    && Array.isArray(candidate.details)
    && (candidate.operationId === null || typeof candidate.operationId === 'string');
}

function transportErrorFromBody(body: unknown, status: number): ApiTransportError {
  if (isApiErrorResource(body)) {
    return new ApiTransportError(
      body.code,
      body.message,
      body.details,
      body.operationId,
      status,
    );
  }

  return new ApiTransportError(
    'HTTP_ERROR',
    `The server returned HTTP ${status}.`,
    [],
    null,
    status,
  );
}

function classifyCommandResult(result: CommandResult): CommandOutcome {
  switch (result.status) {
    case 'ACCEPTED':
      return { kind: 'accepted', result };
    case 'REJECTED':
      return { kind: 'rejected', result, diagnostics: result.diagnostics };
    case 'CONFLICT':
      return { kind: 'conflict', result };
    case 'DEFERRED':
      return { kind: 'deferred', result };
  }
}

export interface ApiClient {
  register(request: RegisterRequest): Promise<UserResource>;
  login(request: LoginRequest): Promise<LoginResponse>;
  logout(): Promise<void>;
  me(): Promise<UserResource>;
  createProject(request: CreateProjectRequest): Promise<ProjectResource>;
  listProjects(): Promise<ProjectResource[]>;
  getProject(projectId: Uuid): Promise<ProjectResource>;
  listProjectMembers(projectId: Uuid): Promise<MemberResource[]>;
  listProjectInvitations(projectId: Uuid): Promise<InvitationResource[]>;
  createProjectInvitation(projectId: Uuid, request: CreateInvitationRequest): Promise<InvitationResource>;
  acceptProjectInvitation(projectId: Uuid, invitationId: Uuid): Promise<MemberResource>;
  revokeProjectInvitation(projectId: Uuid, invitationId: Uuid): Promise<InvitationResource>;
  updateProjectMemberRole(
    projectId: Uuid,
    userId: Uuid,
    request: UpdateMemberRoleRequest,
  ): Promise<MemberResource>;
  removeProjectMember(projectId: Uuid, userId: Uuid): Promise<MemberResource>;
  listMyInvitations(): Promise<InvitationResource[]>;
  getModel(projectId: Uuid): Promise<ModelResource>;
  executeCommand(projectId: Uuid, envelope: OperationEnvelope): Promise<CommandOutcome>;
  listGenerationTargets(projectId: Uuid): Promise<GenerationTargetResource[]>;
  createGenerationTarget(
    projectId: Uuid,
    request: CreateGenerationTargetRequest,
  ): Promise<OperationOutcomeResource<GenerationTargetResource>>;
  listGenerationProfiles(projectId: Uuid): Promise<GenerationProfileResource[]>;
  startGeneration(
    projectId: Uuid,
    request: CreateGenerationRequest,
  ): Promise<OperationOutcomeResource<GenerationRunResource>>;
  getGeneration(projectId: Uuid, runId: Uuid): Promise<OperationOutcomeResource<GenerationRunResource>>;
  downloadGenerationArchive(projectId: Uuid, runId: Uuid): Promise<GenerationArchiveDownload>;
  listDeployments(projectId: Uuid): Promise<DeploymentResource[]>;
  requestDeployment(projectId: Uuid, runId: Uuid): Promise<DeploymentResource>;
  stopDeployment(deploymentId: Uuid): Promise<DeploymentResource>;
}

function archiveFilename(response: Response, fallback: string): string {
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const match = /filename="?([^";]+)"?/i.exec(disposition);
  return match?.[1] ?? fallback;
}

export function createApiClient(
  baseUrl: string = import.meta.env.VITE_API_BASE_URL ?? '',
  fetcher: typeof fetch = fetch,
): ApiClient {
  const base = baseUrl.replace(/\/+$/, '');

  function authorizedInit(path: string, init?: RequestInit): RequestInit {
    const headers = new Headers(init?.headers);
    if (path === '/auth/login' || path === '/auth/register') {
      headers.delete('Authorization');
    } else {
      const authorization = getAuthorizationHeaderValue();
      if (authorization) {
        headers.set('Authorization', authorization);
      }
    }
    return { ...init, headers };
  }

  function clearSessionOnUnauthorized(path: string, response: Response): void {
    if (response.status === 401 && path !== '/auth/login' && path !== '/auth/register') {
      clearSession();
    }
  }

  async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
    let response: Response;
    try {
      response = await fetcher(`${base}${path}`, authorizedInit(path, init));
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : 'The server could not be reached.';
      throw new ApiTransportError('NETWORK_ERROR', message, [], null, null, { cause });
    }

    clearSessionOnUnauthorized(path, response);

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

  async function requestArchive(projectId: Uuid, runId: Uuid): Promise<GenerationArchiveDownload> {
    const path = `/projects/${encodeURIComponent(projectId)}/generations/${encodeURIComponent(runId)}/artifacts`;
    let response: Response;
    try {
      response = await fetcher(`${base}${path}`, authorizedInit(path, { method: 'GET' }));
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : 'The server could not be reached.';
      throw new ApiTransportError('NETWORK_ERROR', message, [], null, null, { cause });
    }

    clearSessionOnUnauthorized(path, response);
    if (!response.ok) {
      const body = await response.json().catch(() => null);
      throw transportErrorFromBody(body, response.status);
    }

    const contentType = response.headers.get('Content-Type') ?? '';
    if (contentType.includes('application/zip')) {
      return {
        kind: 'archive',
        filename: archiveFilename(response, `generation-${runId}.zip`),
        blob: await response.blob(),
      };
    }

    const body = (await response.json().catch(() => null)) as Partial<OperationOutcomeResource<unknown>> | null;
    if (body && typeof body.code === 'string' && typeof body.message === 'string') {
      return { kind: 'rejected', code: body.code, message: body.message };
    }
    return {
      kind: 'rejected',
      code: 'RUN_NOT_COMPLETE',
      message: 'That generation run has no downloadable archive yet.',
    };
  }

  return {
    register(request) {
      return postJson<UserResource>('/auth/register', request);
    },
    login(request) {
      return postJson<LoginResponse>('/auth/login', request);
    },
    async logout() {
      await requestJson<unknown>('/auth/logout', { method: 'POST' });
    },
    me() {
      return requestJson<UserResource>('/auth/me');
    },
    createProject(request) {
      return postJson<ProjectResource>('/projects', request);
    },
    listProjects() {
      return requestJson<ProjectResource[]>('/projects');
    },
    getProject(projectId) {
      return requestJson<ProjectResource>(`/projects/${encodeURIComponent(projectId)}`);
    },
    listProjectMembers(projectId) {
      return requestJson<MemberResource[]>(`/projects/${encodeURIComponent(projectId)}/members`);
    },
    listProjectInvitations(projectId) {
      return requestJson<InvitationResource[]>(`/projects/${encodeURIComponent(projectId)}/invitations`);
    },
    createProjectInvitation(projectId, request) {
      return postJson<InvitationResource>(
        `/projects/${encodeURIComponent(projectId)}/invitations`,
        request,
      );
    },
    acceptProjectInvitation(projectId, invitationId) {
      return requestJson<MemberResource>(
        `/projects/${encodeURIComponent(projectId)}/invitations/${encodeURIComponent(invitationId)}/accept`,
        { method: 'POST' },
      );
    },
    revokeProjectInvitation(projectId, invitationId) {
      return requestJson<InvitationResource>(
        `/projects/${encodeURIComponent(projectId)}/invitations/${encodeURIComponent(invitationId)}/revoke`,
        { method: 'POST' },
      );
    },
    updateProjectMemberRole(projectId, userId, request) {
      const path = `/projects/${encodeURIComponent(projectId)}/members/${encodeURIComponent(userId)}`;
      return requestJson<MemberResource>(path, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      });
    },
    removeProjectMember(projectId, userId) {
      return requestJson<MemberResource>(
        `/projects/${encodeURIComponent(projectId)}/members/${encodeURIComponent(userId)}`,
        { method: 'DELETE' },
      );
    },
    listMyInvitations() {
      return requestJson<InvitationResource[]>('/invitations');
    },
    getModel(projectId) {
      return requestJson<ModelResource>(`/projects/${encodeURIComponent(projectId)}/model`);
    },
    async executeCommand(projectId, envelope) {
      const result = await postJson<CommandResult>(
        `/projects/${encodeURIComponent(projectId)}/commands`,
        envelope,
      );
      return classifyCommandResult(result);
    },
    listGenerationTargets(projectId) {
      return requestJson<GenerationTargetResource[]>(
        `/projects/${encodeURIComponent(projectId)}/generation-targets`,
      );
    },
    createGenerationTarget(projectId, request) {
      return postJson<OperationOutcomeResource<GenerationTargetResource>>(
        `/projects/${encodeURIComponent(projectId)}/generation-targets`,
        request,
      );
    },
    listGenerationProfiles(projectId) {
      return requestJson<GenerationProfileResource[]>(
        `/projects/${encodeURIComponent(projectId)}/generation-profiles`,
      );
    },
    startGeneration(projectId, request) {
      return postJson<OperationOutcomeResource<GenerationRunResource>>(
        `/projects/${encodeURIComponent(projectId)}/generations`,
        request,
      );
    },
    getGeneration(projectId, runId) {
      return requestJson<OperationOutcomeResource<GenerationRunResource>>(
        `/projects/${encodeURIComponent(projectId)}/generations/${encodeURIComponent(runId)}`,
      );
    },
    downloadGenerationArchive(projectId, runId) {
      return requestArchive(projectId, runId);
    },
    listDeployments(projectId) {
      return requestJson<DeploymentResource[]>(
        `/projects/${encodeURIComponent(projectId)}/deployments`,
      );
    },
    requestDeployment(projectId, runId) {
      return postJson<DeploymentResource>(
        `/projects/${encodeURIComponent(projectId)}/deployments`,
        { runId },
      );
    },
    stopDeployment(deploymentId) {
      return requestJson<DeploymentResource>(
        `/deployments/${encodeURIComponent(deploymentId)}/stop`,
        { method: 'POST' },
      );
    },
  };
}
