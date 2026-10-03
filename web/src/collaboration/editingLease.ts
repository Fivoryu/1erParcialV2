import type {
  CommandTarget,
  EditingLeaseConflict,
  EditingLeaseRequest,
  EditingLeaseResource,
} from '../api/types';

export const EDITING_LEASE_HEARTBEAT_MS = 15_000;

interface ForeignLeaseState {
  kind: 'foreign-live' | 'foreign-expired';
  lease: EditingLeaseResource | null;
  conflict: EditingLeaseConflict;
}

export type EditingLeaseState =
  | { kind: 'none' }
  | { kind: 'ours'; lease: EditingLeaseResource; nextHeartbeatAt: number }
  | { kind: 'ours-expired'; lease: EditingLeaseResource }
  | ForeignLeaseState;

const TARGET_TYPES = new Set([
  'CLASSIFIER',
  'ATTRIBUTE',
  'OPERATION',
  'PARAMETER',
  'RELATIONSHIP',
  'RELATIONSHIP_END',
  'CONSTRAINT',
  'DIAGRAM_VIEW',
]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Maps either frozen conflict carrier to the same five-field client shape. */
export function mapEditingLeaseConflict(carrier: unknown): EditingLeaseConflict | null {
  if (!isRecord(carrier) || carrier.code !== 'EXPORT_BLOCKED_EDITING') {
    return null;
  }

  const candidate = Array.isArray(carrier.details) ? carrier.details[0] : carrier.result;
  if (!isRecord(candidate)) {
    return null;
  }

  const { editorDisplayName, targetType, targetId, reclaimable, retryAfterSeconds } = candidate;
  if (typeof editorDisplayName !== 'string'
    || (targetType !== null && typeof targetType !== 'string')
    || (targetId !== null && typeof targetId !== 'string')
    || typeof reclaimable !== 'boolean'
    || typeof retryAfterSeconds !== 'number'
    || !Number.isInteger(retryAfterSeconds)
    || retryAfterSeconds < 0) {
    return null;
  }

  return { editorDisplayName, targetType, targetId, reclaimable, retryAfterSeconds };
}

/** GET is the only carrier that lacks the server's reclaimable decision. */
export function deriveEditingLeaseState(
  lease: EditingLeaseResource | null,
  currentUserId: string | null,
  now: number,
): EditingLeaseState {
  if (lease === null) {
    return { kind: 'none' };
  }
  const expiresAt = Date.parse(lease.expiresAt);
  const live = Number.isFinite(expiresAt) && now < expiresAt;
  if (currentUserId !== null && lease.editorUserId === currentUserId) {
    return live
      ? { kind: 'ours', lease, nextHeartbeatAt: now + EDITING_LEASE_HEARTBEAT_MS }
      : { kind: 'ours-expired', lease };
  }
  const conflict: EditingLeaseConflict = {
    editorDisplayName: lease.editorDisplayName,
    targetType: lease.targetType,
    targetId: lease.targetId,
    reclaimable: !live,
    retryAfterSeconds: live ? Math.max(0, Math.ceil((expiresAt - now) / 1000)) : 0,
  };
  return { kind: live ? 'foreign-live' : 'foreign-expired', lease, conflict };
}

/** Converts a server conflict into notice state without recalculating server liveness. */
export function editingLeaseStateFromConflict(conflict: EditingLeaseConflict): EditingLeaseState {
  return {
    kind: conflict.reclaimable ? 'foreign-expired' : 'foreign-live',
    lease: null,
    conflict,
  };
}

export function decideEditingLeaseHeartbeat(state: EditingLeaseState, now: number): 'renew' | 'none' {
  return state.kind === 'ours' && now >= state.nextHeartbeatAt ? 'renew' : 'none';
}

export function editingLeaseRequestForTarget(target?: CommandTarget | null): EditingLeaseRequest {
  if (!target) {
    return {};
  }
  const targetType = target.scope.toUpperCase();
  return TARGET_TYPES.has(targetType) ? { targetType, targetId: target.id } : {};
}
