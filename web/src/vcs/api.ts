import { ApiTransportError } from '../api/client';
import { clearSession, getAuthorizationHeaderValue } from '../api/session';
import type { ModelResource, Uuid } from '../api/types';

export type VcsCommitKind = 'INITIAL' | 'MANUAL' | 'MERGE';
export type VcsCommitOrigin = 'MANUAL' | 'AI' | 'VOICE' | 'IMPORT' | 'MERGE';
export type VcsConflictKind = 'FIELD_CONFLICT' | 'DELETE_MODIFY' | 'NAME_COLLISION';
export type VcsPermissionAction =
  | 'VIEW'
  | 'CREATE'
  | 'ADD'
  | 'DELETE'
  | 'COMMIT'
  | 'BRANCH'
  | 'MERGE'
  | 'MANAGE_PERMISSIONS';
export type VcsMergeRequestState = 'OPEN' | 'MERGED' | 'CLOSED';

export const VCS_PERMISSION_ACTIONS: readonly VcsPermissionAction[] = [
  'VIEW',
  'CREATE',
  'ADD',
  'DELETE',
  'COMMIT',
  'BRANCH',
  'MERGE',
  'MANAGE_PERMISSIONS',
];

export const VCS_ERROR_CODES = [
  'FORBIDDEN',
  'NOT_FOUND',
  'INVALID_PAYLOAD',
  'BRANCH_NAME_TAKEN',
  'WORKING_COPY_DIRTY',
  'WORKING_COPY_CLEAN',
  'MERGE_CONFLICTS',
  'MERGE_REQUEST_EXISTS',
] as const;

export type VcsErrorCode = (typeof VCS_ERROR_CODES)[number];

export interface VcsStatus {
  branch: string;
  headCommitId: Uuid | null;
  dirty: boolean;
}

export interface VcsBranch {
  name: string;
  isProtected: boolean;
  headCommitId: Uuid | null;
  headMessage: string | null;
  headAuthorDisplayName: string | null;
  headCreatedAt: string | null;
}

export interface VcsCommit {
  commitId: Uuid;
  branchName: string;
  kind: VcsCommitKind;
  origin: VcsCommitOrigin;
  message: string;
  authorUserId: Uuid;
  authorDisplayName: string;
  parentCommitId: Uuid | null;
  secondParentCommitId: Uuid | null;
  stateHash: string;
  createdAt: string;
}

export interface VcsCommitChange {
  changeKind: string;
  targetType: string;
  targetId: Uuid;
  targetName: string;
}

/** A field that differs between two snapshots; values are already formatted for display. */
export interface VcsFieldChange {
  field: string;
  before: string | null;
  after: string | null;
}

/** Field-level change of one element (class, attribute, operation, relationship...). */
export interface VcsElementChange {
  changeKind: string;
  targetType: string;
  targetId: Uuid;
  targetName: string;
  ownerName: string | null;
  fields: VcsFieldChange[];
}

export type VcsCommitAuthorRole = 'AUTHOR' | 'MERGED_BY' | 'CONTRIBUTOR';

export interface VcsCommitAuthor {
  userId: Uuid;
  displayName: string;
  role: VcsCommitAuthorRole;
}

export interface VcsCommitDetail extends VcsCommit {
  changes: VcsCommitChange[];
  /** Absent when talking to a server that predates field-level diffs. */
  details?: VcsElementChange[];
  authors?: VcsCommitAuthor[];
}

export interface VcsMergeConflict {
  kind: VcsConflictKind;
  targetType: string;
  targetId: Uuid;
  targetName: string;
  field: string | null;
  baseValue: unknown;
  sourceValue: unknown;
  targetValue: unknown;
  message: string;
}

export interface VcsMergeRequest {
  mergeRequestId: Uuid;
  title: string;
  state: VcsMergeRequestState;
  sourceBranch: string;
  targetBranch: string;
  sourceCommitId: Uuid | null;
  targetCommitId: Uuid | null;
  mergeBaseCommitId: Uuid | null;
  hasConflicts: boolean;
  conflicts: VcsMergeConflict[];
  openedByDisplayName: string;
  decidedByDisplayName: string | null;
  createdAt: string;
}

/** What a merge request brings; the conflicts are against the target as it is now when the request is open. */
export interface VcsMergeRequestDiff {
  changes: VcsElementChange[];
  commits: VcsCommit[];
  contributors: VcsCommitAuthor[];
  hasConflicts: boolean;
  conflicts: VcsMergeConflict[];
}

export interface VcsProjectPermission {
  userId: Uuid;
  username: string;
  displayName: string;
  actions: VcsPermissionAction[];
}

export interface CreateVcsBranchRequest {
  name: string;
  fromBranch?: string;
}

export interface CreateVcsCommitRequest {
  message: string;
}

export interface CreateVcsMergeRequestRequest {
  sourceBranch: string;
  targetBranch: string;
  title: string;
}

export interface UpdateVcsPermissionsRequest {
  actions: VcsPermissionAction[];
}

export interface VcsCheckoutResource {
  branch: string;
  model: ModelResource;
}

export interface VcsApiClient {
  getStatus(projectId: Uuid): Promise<VcsStatus>;
  listBranches(projectId: Uuid): Promise<VcsBranch[]>;
  createBranch(projectId: Uuid, request: CreateVcsBranchRequest): Promise<VcsBranch>;
  checkoutBranch(projectId: Uuid, branchName: string): Promise<VcsCheckoutResource>;
  updateBranchProtection(projectId: Uuid, branchName: string, isProtected: boolean): Promise<VcsBranch>;
  listCommits(projectId: Uuid, branch?: string, limit?: number): Promise<VcsCommit[]>;
  getCommit(projectId: Uuid, commitId: Uuid): Promise<VcsCommitDetail>;
  createCommit(projectId: Uuid, request: CreateVcsCommitRequest): Promise<VcsCommit>;
  listMergeRequests(projectId: Uuid): Promise<VcsMergeRequest[]>;
  createMergeRequest(projectId: Uuid, request: CreateVcsMergeRequestRequest): Promise<VcsMergeRequest>;
  getMergeRequest(projectId: Uuid, mergeRequestId: Uuid): Promise<VcsMergeRequest>;
  getMergeRequestDiff(projectId: Uuid, mergeRequestId: Uuid): Promise<VcsMergeRequestDiff>;
  mergeMergeRequest(projectId: Uuid, mergeRequestId: Uuid): Promise<VcsMergeRequest>;
  closeMergeRequest(projectId: Uuid, mergeRequestId: Uuid): Promise<VcsMergeRequest>;
  listPermissions(projectId: Uuid): Promise<VcsProjectPermission[]>;
  updatePermissions(
    projectId: Uuid,
    userId: Uuid,
    request: UpdateVcsPermissionsRequest,
  ): Promise<VcsProjectPermission>;
}

export function permissionUpdatePayload(
  checkedActions: readonly VcsPermissionAction[],
): UpdateVcsPermissionsRequest {
  const checked = new Set(checkedActions);
  return { actions: VCS_PERMISSION_ACTIONS.filter((action) => checked.has(action)) };
}

export function permissionTogglePayload(
  currentActions: readonly VcsPermissionAction[],
  action: VcsPermissionAction,
  checked: boolean,
): UpdateVcsPermissionsRequest {
  const nextActions = checked
    ? [...currentActions, action]
    : currentActions.filter((current) => current !== action);
  return permissionUpdatePayload(nextActions);
}

export function vcsErrorMessage(error: unknown, fallback: string): string {
  if (!(error instanceof ApiTransportError)) {
    return fallback;
  }
  if (error.code === 'WORKING_COPY_CLEAN') {
    return 'No hay cambios para confirmar.';
  }
  return error.message.trim().length > 0 ? error.message : fallback;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function errorFromResponse(body: unknown, status: number): ApiTransportError {
  if (isRecord(body)
    && typeof body.code === 'string'
    && typeof body.message === 'string'
    && Array.isArray(body.details)
    && (body.operationId === null || typeof body.operationId === 'string')) {
    const details = body.details.filter(isRecord);
    return new ApiTransportError(
      body.code,
      body.message,
      details,
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

export function createVcsApiClient(
  baseUrl: string = import.meta.env.VITE_API_BASE_URL ?? '',
  fetcher: typeof fetch = fetch,
): VcsApiClient {
  const base = baseUrl.replace(/\/+$/, '');

  async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers);
    const authorization = getAuthorizationHeaderValue();
    if (authorization) {
      headers.set('Authorization', authorization);
    } else {
      headers.delete('Authorization');
    }

    let response: Response;
    try {
      response = await fetcher(`${base}${path}`, { ...init, headers });
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : 'No se pudo conectar con el servidor.';
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
      throw errorFromResponse(body, response.status);
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

  function putJson<T>(path: string, payload: unknown): Promise<T> {
    return requestJson<T>(path, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
  }

  function projectPath(projectId: Uuid): string {
    return `/projects/${encodeURIComponent(projectId)}/vcs`;
  }

  return {
    getStatus(projectId) {
      return requestJson<VcsStatus>(`${projectPath(projectId)}/status`);
    },
    listBranches(projectId) {
      return requestJson<VcsBranch[]>(`${projectPath(projectId)}/branches`);
    },
    createBranch(projectId, request) {
      return postJson<VcsBranch>(`${projectPath(projectId)}/branches`, request);
    },
    checkoutBranch(projectId, branchName) {
      return postJson<VcsCheckoutResource>(
        `${projectPath(projectId)}/branches/${encodeURIComponent(branchName)}/checkout`,
        {},
      );
    },
    updateBranchProtection(projectId, branchName, isProtected) {
      return putJson<VcsBranch>(
        `${projectPath(projectId)}/branches/${encodeURIComponent(branchName)}`,
        { isProtected },
      );
    },
    listCommits(projectId, branch, limit) {
      const query = new URLSearchParams();
      if (branch !== undefined) {
        query.set('branch', branch);
      }
      if (limit !== undefined) {
        query.set('limit', String(limit));
      }
      const suffix = query.size > 0 ? `?${query.toString()}` : '';
      return requestJson<VcsCommit[]>(`${projectPath(projectId)}/commits${suffix}`);
    },
    getCommit(projectId, commitId) {
      return requestJson<VcsCommitDetail>(
        `${projectPath(projectId)}/commits/${encodeURIComponent(commitId)}`,
      );
    },
    createCommit(projectId, request) {
      return postJson<VcsCommit>(`${projectPath(projectId)}/commits`, request);
    },
    listMergeRequests(projectId) {
      return requestJson<VcsMergeRequest[]>(`${projectPath(projectId)}/merge-requests`);
    },
    createMergeRequest(projectId, request) {
      return postJson<VcsMergeRequest>(`${projectPath(projectId)}/merge-requests`, request);
    },
    getMergeRequest(projectId, mergeRequestId) {
      return requestJson<VcsMergeRequest>(
        `${projectPath(projectId)}/merge-requests/${encodeURIComponent(mergeRequestId)}`,
      );
    },
    getMergeRequestDiff(projectId, mergeRequestId) {
      return requestJson<VcsMergeRequestDiff>(
        `${projectPath(projectId)}/merge-requests/${encodeURIComponent(mergeRequestId)}/diff`,
      );
    },
    mergeMergeRequest(projectId, mergeRequestId) {
      return postJson<VcsMergeRequest>(
        `${projectPath(projectId)}/merge-requests/${encodeURIComponent(mergeRequestId)}/merge`,
        {},
      );
    },
    closeMergeRequest(projectId, mergeRequestId) {
      return postJson<VcsMergeRequest>(
        `${projectPath(projectId)}/merge-requests/${encodeURIComponent(mergeRequestId)}/close`,
        {},
      );
    },
    listPermissions(projectId) {
      return requestJson<VcsProjectPermission[]>(`${projectPath(projectId)}/permissions`);
    },
    updatePermissions(projectId, userId, request) {
      return putJson<VcsProjectPermission>(
        `${projectPath(projectId)}/permissions/${encodeURIComponent(userId)}`,
        request,
      );
    },
  };
}
