import type { ChatSessionResource, Uuid } from '../api/types';
import type { SessionAiStatus } from './chatState';

const TERMINAL_AI_STATES = new Set<SessionAiStatus['state']>(['COMPLETED', 'FAILED', 'CANCELLED']);

/** Refresh only when the status belongs to a session in the currently visible diagram view. */
export function shouldRefreshDiagramMemories(
  status: SessionAiStatus,
  sessions: readonly ChatSessionResource[],
  viewId: Uuid | null,
): boolean {
  return viewId !== null
    && TERMINAL_AI_STATES.has(status.state)
    && sessions.some((session) => session.id === status.sessionId && session.viewId === viewId);
}

/** Reject asynchronous memory responses once either their project or view is no longer open. */
export function isCurrentMemoryRefreshScope(
  scope: { projectId: Uuid; viewId: Uuid },
  currentProjectId: Uuid | null | undefined,
  currentViewId: Uuid | null,
): boolean {
  return scope.projectId === currentProjectId && scope.viewId === currentViewId;
}
