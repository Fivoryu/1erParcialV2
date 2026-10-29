import { describe, expect, it, vi } from 'vitest';
import { ApiTransportError } from '../api/client';
import { localizedErrorMessage } from '../api/errorMessages';
import {
  createVcsApiClient,
  permissionTogglePayload,
  permissionUpdatePayload,
  VCS_ERROR_CODES,
  VCS_PERMISSION_ACTIONS,
  vcsErrorMessage,
  type VcsPermissionAction,
} from './api';

const projectId = 'project-1';
const userId = 'user-1';
const commitId = 'commit-1';
const mergeRequestId = 'merge-request-1';

function mockFetch() {
  return vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit): Promise<Response> => (
    new Response('null', { status: 200, headers: { 'Content-Type': 'application/json' } })
  ));
}

describe('version-control API contract', () => {
  it('sends the frozen VCS routes and request bodies', async () => {
    const fetcher = mockFetch();
    const api = createVcsApiClient('/api/', fetcher);

    await api.getStatus(projectId);
    await api.listBranches(projectId);
    await api.createBranch(projectId, { name: 'feature', fromBranch: 'main' });
    await api.checkoutBranch(projectId, 'feature/review');
    await api.updateBranchProtection(projectId, 'main', true);
    await api.listCommits(projectId, 'main', 20);
    await api.getCommit(projectId, commitId);
    await api.compareCommits(projectId, commitId, 'commit-2');
    await api.compareCommits(projectId, null, 'commit-2');
    await api.createCommit(projectId, { message: 'Add a class' });
    await api.listMergeRequests(projectId);
    await api.createMergeRequest(projectId, {
      sourceBranch: 'feature', targetBranch: 'main', title: 'Review the change',
    });
    await api.getMergeRequest(projectId, mergeRequestId);
    await api.getMergeRequestDiff(projectId, mergeRequestId);
    await api.mergeMergeRequest(projectId, mergeRequestId);
    await api.closeMergeRequest(projectId, mergeRequestId);
    await api.listPermissions(projectId);
    await api.updatePermissions(projectId, userId, { actions: ['VIEW', 'COMMIT'] });

    const requests = fetcher.mock.calls.map(([url, init]) => ({
      url: String(url),
      method: init?.method ?? 'GET',
      body: init?.body ?? null,
    }));
    expect(requests).toEqual([
      { url: '/api/projects/project-1/vcs/status', method: 'GET', body: null },
      { url: '/api/projects/project-1/vcs/branches', method: 'GET', body: null },
      {
        url: '/api/projects/project-1/vcs/branches', method: 'POST',
        body: JSON.stringify({ name: 'feature', fromBranch: 'main' }),
      },
      {
        url: '/api/projects/project-1/vcs/branches/feature%2Freview/checkout', method: 'POST', body: '{}',
      },
      {
        url: '/api/projects/project-1/vcs/branches/main', method: 'PUT',
        body: JSON.stringify({ isProtected: true }),
      },
      { url: '/api/projects/project-1/vcs/commits?branch=main&limit=20', method: 'GET', body: null },
      { url: '/api/projects/project-1/vcs/commits/commit-1', method: 'GET', body: null },
      { url: '/api/projects/project-1/vcs/repo/compare?fromCommitId=commit-1&toCommitId=commit-2', method: 'GET', body: null },
      { url: '/api/projects/project-1/vcs/repo/compare?toCommitId=commit-2', method: 'GET', body: null },
      {
        url: '/api/projects/project-1/vcs/commits', method: 'POST',
        body: JSON.stringify({ message: 'Add a class' }),
      },
      { url: '/api/projects/project-1/vcs/merge-requests', method: 'GET', body: null },
      {
        url: '/api/projects/project-1/vcs/merge-requests', method: 'POST',
        body: JSON.stringify({ sourceBranch: 'feature', targetBranch: 'main', title: 'Review the change' }),
      },
      { url: '/api/projects/project-1/vcs/merge-requests/merge-request-1', method: 'GET', body: null },
      { url: '/api/projects/project-1/vcs/merge-requests/merge-request-1/diff', method: 'GET', body: null },
      {
        url: '/api/projects/project-1/vcs/merge-requests/merge-request-1/merge', method: 'POST', body: '{}',
      },
      {
        url: '/api/projects/project-1/vcs/merge-requests/merge-request-1/close', method: 'POST', body: '{}',
      },
      { url: '/api/projects/project-1/vcs/permissions', method: 'GET', body: null },
      {
        url: '/api/projects/project-1/vcs/permissions/user-1', method: 'PUT',
        body: JSON.stringify({ actions: ['VIEW', 'COMMIT'] }),
      },
    ]);
  });

  it('builds permission updates only from checked actions in contract order', () => {
    const checkedActions: VcsPermissionAction[] = ['MERGE', 'VIEW', 'VIEW', 'MANAGE_PERMISSIONS'];

    expect(permissionUpdatePayload(checkedActions)).toEqual({
      actions: VCS_PERMISSION_ACTIONS.filter((action) => checkedActions.includes(action)),
    });
    expect(permissionUpdatePayload(checkedActions).actions).toEqual(['VIEW', 'MERGE', 'MANAGE_PERMISSIONS']);
  });

  it('builds permission-toggle payloads from the effective checked set', () => {
    expect(permissionTogglePayload(['VIEW', 'ADD', 'MERGE'], 'COMMIT', true)).toEqual({
      actions: ['VIEW', 'ADD', 'COMMIT', 'MERGE'],
    });
    expect(permissionTogglePayload(['VIEW', 'ADD', 'MERGE'], 'ADD', false)).toEqual({
      actions: ['VIEW', 'MERGE'],
    });
  });

  it('keeps each frozen error code on the readable error path', () => {
    for (const code of VCS_ERROR_CODES) {
      const message = `Readable response for ${code}.`;
      const error = new ApiTransportError(code, message, [], null, 409);
      expect(vcsErrorMessage(error, 'Fallback message')).toBe(
        code === 'WORKING_COPY_CLEAN' ? 'No hay cambios para confirmar.' : localizedErrorMessage(code, message),
      );
    }
  });
});
