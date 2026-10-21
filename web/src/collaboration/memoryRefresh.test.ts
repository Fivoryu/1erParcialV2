import { describe, expect, it } from 'vitest';
import type { ChatSessionResource } from '../api/types';
import type { SessionAiStatus } from './chatState';
import { isCurrentMemoryRefreshScope, shouldRefreshDiagramMemories } from './memoryRefresh';

const projectId = '11111111-1111-4111-8111-111111111111';
const viewId = '22222222-2222-4222-8222-222222222222';
const otherViewId = '33333333-3333-4333-8333-333333333333';
const sessionId = '44444444-4444-4444-8444-444444444444';
const otherSessionId = '55555555-5555-4555-8555-555555555555';

const sessions: ChatSessionResource[] = [
  {
    id: sessionId,
    viewId,
    title: 'General',
    createdByUserId: '66666666-6666-4666-8666-666666666666',
    createdAt: '2026-02-01T14:00:00Z',
  },
  {
    id: otherSessionId,
    viewId: otherViewId,
    title: 'Other view',
    createdByUserId: '66666666-6666-4666-8666-666666666666',
    createdAt: '2026-02-01T14:00:00Z',
  },
];

function status(state: SessionAiStatus['state'], id = sessionId): SessionAiStatus {
  return { sessionId: id, state };
}

describe('diagram memory refresh', () => {
  it.each(['COMPLETED', 'FAILED', 'CANCELLED'] as const)(
    'refreshes the current view after a %s AI terminal event, including collaborator sessions',
    (state) => {
      expect(shouldRefreshDiagramMemories(status(state), sessions, viewId)).toBe(true);
    },
  );

  it.each(['QUEUED', 'RUNNING'] as const)('does not refresh for the non-terminal %s state', (state) => {
    expect(shouldRefreshDiagramMemories(status(state), sessions, viewId)).toBe(false);
  });

  it('ignores unknown sessions and sessions belonging to another view', () => {
    expect(shouldRefreshDiagramMemories(status('COMPLETED', '77777777-7777-4777-8777-777777777777'), sessions, viewId))
      .toBe(false);
    expect(shouldRefreshDiagramMemories(status('FAILED', otherSessionId), sessions, viewId)).toBe(false);
    expect(shouldRefreshDiagramMemories(status('COMPLETED'), sessions, null)).toBe(false);
  });

  it('accepts only refresh results for the project and view that are still current', () => {
    const scope = { projectId, viewId };
    expect(isCurrentMemoryRefreshScope(scope, projectId, viewId)).toBe(true);
    expect(isCurrentMemoryRefreshScope(scope, '88888888-8888-4888-8888-888888888888', viewId)).toBe(false);
    expect(isCurrentMemoryRefreshScope(scope, projectId, otherViewId)).toBe(false);
    expect(isCurrentMemoryRefreshScope(scope, projectId, null)).toBe(false);
  });
});
