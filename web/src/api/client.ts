import type {
  ApiErrorResource,
  ChatAttachmentResource,
  ChatMessageResource,
  ChatProposalResource,
  CommandOutcome,
  CompactionOutcomeResource,
  ContextMeterResource,
  DeletedSessionHistoryResource,
  DiagramMemoryResource,
  ForgottenMemoriesResource,
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
  VerificationSummaryResource,
  HelpGuideRequest,
  HelpGuideResponse,
  GenerationReadinessResource,
  InvitationResource,
  MemberResource,
  ModelResource,
  ModelValidationResource,
  OperationEnvelope,
  OperationOutcomeResource,
  ChatSessionResource,
  MemoryExtractionResource,
  ProjectResource,
  RegisterRequest,
  LoginRequest,
  LoginResponse,
  UpdateMemberRoleRequest,
  UserResource,
  Uuid,
  XmiImportRequest,
  XmiImportResource,
  XmiExportResource,
  XmiPreviewRequest,
  XmiPreviewResource,
} from './types';
import type { EditingLeaseEnvelope, EditingLeaseRequest } from './types';
import { clearSession, getAuthorizationHeaderValue } from './session';
import { localizedErrorMessage } from './errorMessages';

export interface AskAiResponse {
  state: 'QUEUED';
}

export class ApiTransportError extends Error {
  readonly kind = 'transport';
  /** The server's own text, kept for diagnosis; `message` holds the Spanish text the UI shows. */
  readonly serverMessage: string;

  constructor(
    readonly code: string,
    message: string,
    readonly details: Array<Record<string, unknown>>,
    readonly operationId: Uuid | null,
    readonly status: number | null,
    options?: ErrorOptions,
  ) {
    super(localizedErrorMessage(code, message), options);
    this.name = 'ApiTransportError';
    this.serverMessage = message;
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
    `El servidor respondió con HTTP ${status}.`,
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

function proposalsPath(projectId: Uuid, sessionId: Uuid): string {
  return `/projects/${encodeURIComponent(projectId)}/sessions/${encodeURIComponent(sessionId)}/proposals`;
}

export interface ApiClient {
  register(request: RegisterRequest): Promise<UserResource>;
  login(request: LoginRequest): Promise<LoginResponse>;
  logout(): Promise<void>;
  me(): Promise<UserResource>;
  askHelpGuide(request: HelpGuideRequest): Promise<HelpGuideResponse>;
  createProject(request: CreateProjectRequest): Promise<ProjectResource>;
  listProjects(): Promise<ProjectResource[]>;
  getProject(projectId: Uuid): Promise<ProjectResource>;
  listProjectMessages(projectId: Uuid): Promise<ChatMessageResource[]>;
  postProjectMessage(projectId: Uuid, body: string): Promise<ChatMessageResource>;
  listSessions(projectId: Uuid, viewId: Uuid): Promise<ChatSessionResource[]>;
  createSession(projectId: Uuid, viewId: Uuid, title: string): Promise<ChatSessionResource>;
  listSessionMessages(projectId: Uuid, sessionId: Uuid): Promise<ChatMessageResource[]>;
  postSessionMessage(
    projectId: Uuid,
    sessionId: Uuid,
    body: string,
    attachmentIds?: Uuid[],
  ): Promise<ChatMessageResource>;
  askAi(projectId: Uuid, sessionId: Uuid, messageId: Uuid): Promise<AskAiResponse>;
  listProposals(projectId: Uuid, sessionId: Uuid): Promise<ChatProposalResource[]>;
  proposeChange(projectId: Uuid, sessionId: Uuid, utterance: string): Promise<ChatProposalResource>;
  acceptProposal(projectId: Uuid, sessionId: Uuid, proposalId: Uuid): Promise<ChatProposalResource>;
  rejectProposal(projectId: Uuid, sessionId: Uuid, proposalId: Uuid): Promise<ChatProposalResource>;
  deleteSessionHistory(projectId: Uuid, sessionId: Uuid): Promise<DeletedSessionHistoryResource>;
  getContextMeter(projectId: Uuid, sessionId: Uuid): Promise<ContextMeterResource>;
  compactSession(projectId: Uuid, sessionId: Uuid): Promise<CompactionOutcomeResource>;
  listMemories(projectId: Uuid, viewId: Uuid, status?: string): Promise<DiagramMemoryResource[]>;
  extractMemories(projectId: Uuid, viewId: Uuid): Promise<MemoryExtractionResource>;
  forgetMemory(projectId: Uuid, viewId: Uuid, memoryId: Uuid): Promise<ForgottenMemoriesResource>;
  forgetAllMemories(projectId: Uuid, viewId: Uuid): Promise<ForgottenMemoriesResource>;
  uploadAttachment(projectId: Uuid, sessionId: Uuid, file: File): Promise<ChatAttachmentResource>;
  getAttachmentBytes(projectId: Uuid, sessionId: Uuid, attachmentId: Uuid): Promise<Blob>;
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
  getModelValidation(projectId: Uuid): Promise<ModelValidationResource>;
  getGenerationReadiness(projectId: Uuid, versionId: Uuid): Promise<GenerationReadinessResource>;
  previewXmi(projectId: Uuid, request: XmiPreviewRequest): Promise<XmiPreviewResource>;
  importXmi(projectId: Uuid, request: XmiImportRequest): Promise<XmiImportResource>;
  exportXmi(projectId: Uuid): Promise<XmiExportResource>;
  reportProjectPresenceTouch(projectId: Uuid, targetElementId: Uuid | null): Promise<void>;
  reportProjectCursorPosition(projectId: Uuid, x: number, y: number): Promise<void>;
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
  verifyGeneration(projectId: Uuid, runId: Uuid): Promise<OperationOutcomeResource<VerificationSummaryResource>>;
  downloadGenerationArchive(projectId: Uuid, runId: Uuid): Promise<GenerationArchiveDownload>;
  listDeployments(projectId: Uuid): Promise<DeploymentResource[]>;
  requestDeployment(projectId: Uuid, runId: Uuid): Promise<DeploymentResource>;
  stopDeployment(deploymentId: Uuid): Promise<DeploymentResource>;
  getEditingLease(projectId: Uuid): Promise<EditingLeaseEnvelope>;
  acquireEditingLease(projectId: Uuid, request?: EditingLeaseRequest): Promise<EditingLeaseEnvelope>;
  renewEditingLease(projectId: Uuid): Promise<EditingLeaseEnvelope>;
  reclaimEditingLease(projectId: Uuid, request?: EditingLeaseRequest): Promise<EditingLeaseEnvelope>;
  releaseEditingLease(projectId: Uuid): Promise<EditingLeaseEnvelope>;
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
      const message = cause instanceof Error ? cause.message : 'No se pudo conectar con el servidor.';
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

  async function requestNoContent(path: string, init?: RequestInit): Promise<void> {
    let response: Response;
    try {
      response = await fetcher(`${base}${path}`, authorizedInit(path, init));
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : 'No se pudo conectar con el servidor.';
      throw new ApiTransportError('NETWORK_ERROR', message, [], null, null, { cause });
    }

    clearSessionOnUnauthorized(path, response);
    if (response.status === 204) {
      return;
    }

    const body = await response.json().catch(() => null) as unknown;
    throw transportErrorFromBody(body, response.status);
  }

  async function requestBlob(path: string, init?: RequestInit): Promise<Blob> {
    let response: Response;
    try {
      response = await fetcher(`${base}${path}`, authorizedInit(path, init));
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : 'No se pudo conectar con el servidor.';
      throw new ApiTransportError('NETWORK_ERROR', message, [], null, null, { cause });
    }

    clearSessionOnUnauthorized(path, response);
    if (!response.ok) {
      const body = await response.json().catch(() => null) as unknown;
      throw transportErrorFromBody(body, response.status);
    }
    return response.blob();
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
      const message = cause instanceof Error ? cause.message : 'No se pudo conectar con el servidor.';
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
    askHelpGuide(request) {
      return postJson<HelpGuideResponse>('/help/guide', request);
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
    listProjectMessages(projectId) {
      return requestJson<ChatMessageResource[]>(`/projects/${encodeURIComponent(projectId)}/messages`);
    },
    postProjectMessage(projectId, body) {
      return postJson<ChatMessageResource>(
        `/projects/${encodeURIComponent(projectId)}/messages`,
        { body },
      );
    },
    listSessions(projectId, viewId) {
      return requestJson<ChatSessionResource[]>(
        `/projects/${encodeURIComponent(projectId)}/views/${encodeURIComponent(viewId)}/sessions`,
      );
    },
    createSession(projectId, viewId, title) {
      return postJson<ChatSessionResource>(
        `/projects/${encodeURIComponent(projectId)}/views/${encodeURIComponent(viewId)}/sessions`,
        { title },
      );
    },
    listSessionMessages(projectId, sessionId) {
      return requestJson<ChatMessageResource[]>(
        `/projects/${encodeURIComponent(projectId)}/sessions/${encodeURIComponent(sessionId)}/messages`,
      );
    },
    postSessionMessage(projectId, sessionId, body, attachmentIds) {
      const payload = attachmentIds && attachmentIds.length > 0 ? { body, attachmentIds } : { body };
      return postJson<ChatMessageResource>(
        `/projects/${encodeURIComponent(projectId)}/sessions/${encodeURIComponent(sessionId)}/messages`,
        payload,
      );
    },
    askAi(projectId, sessionId, messageId) {
      return postJson<AskAiResponse>(
        `/projects/${encodeURIComponent(projectId)}/sessions/${encodeURIComponent(sessionId)}/ask`,
        { messageId },
      );
    },
    listProposals(projectId, sessionId) {
      return requestJson<ChatProposalResource[]>(`${proposalsPath(projectId, sessionId)}`);
    },
    proposeChange(projectId, sessionId, utterance) {
      return postJson<ChatProposalResource>(proposalsPath(projectId, sessionId), { utterance });
    },
    acceptProposal(projectId, sessionId, proposalId) {
      return requestJson<ChatProposalResource>(
        `${proposalsPath(projectId, sessionId)}/${encodeURIComponent(proposalId)}/accept`,
        { method: 'POST' },
      );
    },
    rejectProposal(projectId, sessionId, proposalId) {
      return requestJson<ChatProposalResource>(
        `${proposalsPath(projectId, sessionId)}/${encodeURIComponent(proposalId)}/reject`,
        { method: 'POST' },
      );
    },
    deleteSessionHistory(projectId, sessionId) {
      return requestJson<DeletedSessionHistoryResource>(
        `/projects/${encodeURIComponent(projectId)}/sessions/${encodeURIComponent(sessionId)}`,
        { method: 'DELETE' },
      );
    },
    getContextMeter(projectId, sessionId) {
      return requestJson<ContextMeterResource>(
        `/projects/${encodeURIComponent(projectId)}/sessions/${encodeURIComponent(sessionId)}/meter`,
      );
    },
    compactSession(projectId, sessionId) {
      return requestJson<CompactionOutcomeResource>(
        `/projects/${encodeURIComponent(projectId)}/sessions/${encodeURIComponent(sessionId)}/compact`,
        { method: 'POST' },
      );
    },
    listMemories(projectId, viewId, status) {
      const query = status ? `?status=${encodeURIComponent(status)}` : '';
      return requestJson<DiagramMemoryResource[]>(
        `/projects/${encodeURIComponent(projectId)}/views/${encodeURIComponent(viewId)}/memories${query}`,
      );
    },
    extractMemories(projectId, viewId) {
      return requestJson<MemoryExtractionResource>(
        `/projects/${encodeURIComponent(projectId)}/views/${encodeURIComponent(viewId)}/memories/extract`,
        { method: 'POST' },
      );
    },
    forgetMemory(projectId, viewId, memoryId) {
      return requestJson<ForgottenMemoriesResource>(
        `/projects/${encodeURIComponent(projectId)}/views/${encodeURIComponent(viewId)}/memories/${encodeURIComponent(memoryId)}`,
        { method: 'DELETE' },
      );
    },
    forgetAllMemories(projectId, viewId) {
      return requestJson<ForgottenMemoriesResource>(
        `/projects/${encodeURIComponent(projectId)}/views/${encodeURIComponent(viewId)}/memories`,
        { method: 'DELETE' },
      );
    },
    async uploadAttachment(projectId, sessionId, file) {
      const path = `/projects/${encodeURIComponent(projectId)}/sessions/${encodeURIComponent(sessionId)}/attachments`;
      const form = new FormData();
      form.append('file', file);
      return requestJson<ChatAttachmentResource>(path, { method: 'POST', body: form });
    },
    getAttachmentBytes(projectId, sessionId, attachmentId) {
      return requestBlob(
        `/projects/${encodeURIComponent(projectId)}/sessions/${encodeURIComponent(sessionId)}/attachments/${encodeURIComponent(attachmentId)}`,
      );
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
    getModelValidation(projectId) {
      return requestJson<ModelValidationResource>(
        `/projects/${encodeURIComponent(projectId)}/model/validation`,
      );
    },
    getGenerationReadiness(projectId, versionId) {
      return requestJson<GenerationReadinessResource>(
        `/projects/${encodeURIComponent(projectId)}/generation-readiness?versionId=${encodeURIComponent(versionId)}`,
      );
    },
    previewXmi(projectId, request) {
      return postJson<XmiPreviewResource>(
        `/projects/${encodeURIComponent(projectId)}/xmi/preview`,
        request,
      );
    },
    importXmi(projectId, request) {
      return postJson<XmiImportResource>(
        `/projects/${encodeURIComponent(projectId)}/xmi/import`,
        request,
      );
    },
    exportXmi(projectId) {
      // The backend declares no @RequestBody, so the POST carries no body.
      return requestJson<XmiExportResource>(
        `/projects/${encodeURIComponent(projectId)}/xmi/export`,
        { method: 'POST' },
      );
    },
    reportProjectPresenceTouch(projectId, targetElementId) {
      return requestNoContent(
        `/projects/${encodeURIComponent(projectId)}/presence`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ targetElementId }),
        },
      );
    },
    reportProjectCursorPosition(projectId, x, y) {
      return requestNoContent(
        `/projects/${encodeURIComponent(projectId)}/presence`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ cursorX: x, cursorY: y }),
        },
      );
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
    async verifyGeneration(projectId, runId) {
      const outcome = await requestJson<OperationOutcomeResource<VerificationSummaryResource>>(
        `/projects/${encodeURIComponent(projectId)}/generations/${encodeURIComponent(runId)}/verification`,
        { method: 'POST' },
      );
      return outcome.outcome === 'REJECTED'
        ? { ...outcome, message: localizedErrorMessage(outcome.code, outcome.message) }
        : outcome;
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
    getEditingLease(projectId) {
      return requestJson<EditingLeaseEnvelope>(
        `/projects/${encodeURIComponent(projectId)}/editing-lease`,
      );
    },
    acquireEditingLease(projectId, request = {}) {
      return postJson<EditingLeaseEnvelope>(
        `/projects/${encodeURIComponent(projectId)}/editing-lease`,
        request,
      );
    },
    renewEditingLease(projectId) {
      return postJson<EditingLeaseEnvelope>(
        `/projects/${encodeURIComponent(projectId)}/editing-lease/renew`,
        {},
      );
    },
    reclaimEditingLease(projectId, request = {}) {
      return postJson<EditingLeaseEnvelope>(
        `/projects/${encodeURIComponent(projectId)}/editing-lease/reclaim`,
        request,
      );
    },
    releaseEditingLease(projectId) {
      return requestJson<EditingLeaseEnvelope>(
        `/projects/${encodeURIComponent(projectId)}/editing-lease`,
        { method: 'DELETE' },
      );
    },
  };
}
