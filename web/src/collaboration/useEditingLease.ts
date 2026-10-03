import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiTransportError, createApiClient } from '../api/client';
import type { CommandTarget, EditingLeaseConflict, EditingLeaseEnvelope, Uuid } from '../api/types';
import {
  decideEditingLeaseHeartbeat,
  deriveEditingLeaseState,
  editingLeaseRequestForTarget,
  editingLeaseStateFromConflict,
  EDITING_LEASE_HEARTBEAT_MS,
  mapEditingLeaseConflict,
  type EditingLeaseState,
} from './editingLease';

const api = createApiClient();

export interface EditingLeaseAdapter {
  conflict: EditingLeaseConflict | null;
  reclaimBusy: boolean;
  acquireForEdit(target?: CommandTarget): void;
  recordConflict(conflict: EditingLeaseConflict): void;
  reclaim(): Promise<void>;
}

export function useEditingLease(projectId: Uuid | undefined, currentUserId: string | null): EditingLeaseAdapter {
  const [state, setState] = useState<EditingLeaseState>({ kind: 'none' });
  const [reclaimBusy, setReclaimBusy] = useState(false);
  const reclaimBusyRef = useRef(false);
  const renewingRef = useRef(false);
  const acquireSequenceRef = useRef(0);
  const mountedRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const acceptCurrent = useCallback((envelope: EditingLeaseEnvelope) => {
    if (mountedRef.current) {
      setState(deriveEditingLeaseState(envelope.lease, currentUserId, Date.now()));
    }
  }, [currentUserId]);

  const acceptOwnedLease = useCallback((envelope: EditingLeaseEnvelope) => {
    if (!mountedRef.current) {
      return;
    }
    const lease = envelope.lease;
    setState(lease === null
      ? { kind: 'none' }
      : { kind: 'ours', lease, nextHeartbeatAt: Date.now() + EDITING_LEASE_HEARTBEAT_MS });
  }, []);

  const refreshCurrent = useCallback(async () => {
    if (!projectId || !mountedRef.current) {
      return;
    }
    acceptCurrent(await api.getEditingLease(projectId));
  }, [acceptCurrent, projectId]);

  useEffect(() => {
    if (!projectId) {
      if (mountedRef.current) {
        setState({ kind: 'none' });
      }
      return undefined;
    }

    let active = true;
    void api.getEditingLease(projectId)
      .then((envelope) => {
        if (active && mountedRef.current) {
          acceptCurrent(envelope);
        }
      })
      .catch(() => undefined);

    return () => {
      active = false;
      void api.releaseEditingLease(projectId).catch(() => undefined);
    };
  }, [acceptCurrent, projectId]);

  useEffect(() => {
    if (!projectId || state.kind !== 'ours') {
      return undefined;
    }

    const timer = window.setInterval(() => {
      const now = Date.now();
      if (decideEditingLeaseHeartbeat(state, now) !== 'renew' || renewingRef.current) {
        return;
      }
      renewingRef.current = true;
      if (mountedRef.current) {
        setState((current) => current.kind === 'ours'
          ? { ...current, nextHeartbeatAt: now + EDITING_LEASE_HEARTBEAT_MS }
          : current);
      }
      void api.renewEditingLease(projectId)
        .then(acceptOwnedLease)
        .catch((cause: unknown) => {
          if (mountedRef.current && cause instanceof ApiTransportError && cause.status === 409) {
            void refreshCurrent().catch(() => {
              if (mountedRef.current) {
                setState({ kind: 'none' });
              }
            });
          }
        })
        .finally(() => {
          renewingRef.current = false;
        });
    }, EDITING_LEASE_HEARTBEAT_MS);
    return () => window.clearInterval(timer);
  }, [acceptOwnedLease, projectId, refreshCurrent, state]);

  useEffect(() => {
    if (!projectId || state.kind !== 'foreign-live') {
      return undefined;
    }

    const delay = state.lease === null
      ? state.conflict.retryAfterSeconds * 1000
      : Math.max(0, Date.parse(state.lease.expiresAt) - Date.now());
    const timer = window.setTimeout(() => {
      void refreshCurrent().catch(() => undefined);
    }, delay);
    return () => window.clearTimeout(timer);
  }, [projectId, refreshCurrent, state]);

  const acquireForEdit = useCallback((target?: CommandTarget) => {
    if (!projectId) {
      return;
    }
    const sequence = acquireSequenceRef.current + 1;
    acquireSequenceRef.current = sequence;
    void api.acquireEditingLease(projectId, editingLeaseRequestForTarget(target))
      .then((envelope) => {
        if (mountedRef.current && acquireSequenceRef.current === sequence) {
          acceptOwnedLease(envelope);
        }
      })
      .catch((cause: unknown) => {
        if (!mountedRef.current || acquireSequenceRef.current !== sequence) {
          return;
        }
        if (cause instanceof ApiTransportError && cause.status === 409) {
          const conflict = mapEditingLeaseConflict(cause);
          setState(conflict ? editingLeaseStateFromConflict(conflict) : { kind: 'none' });
        }
      });
  }, [acceptOwnedLease, projectId]);

  const recordConflict = useCallback((conflict: EditingLeaseConflict) => {
    if (mountedRef.current) {
      setState(editingLeaseStateFromConflict(conflict));
    }
  }, []);

  const reclaim = useCallback(async () => {
    if (!projectId || reclaimBusyRef.current || !mountedRef.current) {
      return;
    }
    reclaimBusyRef.current = true;
    setReclaimBusy(true);
    const conflict = state.kind === 'foreign-live' || state.kind === 'foreign-expired' ? state.conflict : null;
    const request = conflict?.targetType && conflict.targetId
      ? { targetType: conflict.targetType, targetId: conflict.targetId }
      : {};
    try {
      acceptOwnedLease(await api.reclaimEditingLease(projectId, request));
    } catch (cause: unknown) {
      if (mountedRef.current && cause instanceof ApiTransportError && cause.status === 409) {
        const nextConflict = mapEditingLeaseConflict(cause);
        if (nextConflict) {
          setState(editingLeaseStateFromConflict(nextConflict));
        } else {
          void refreshCurrent().catch(() => undefined);
        }
      }
    } finally {
      reclaimBusyRef.current = false;
      if (mountedRef.current) {
        setReclaimBusy(false);
      }
    }
  }, [acceptOwnedLease, projectId, refreshCurrent, state]);

  const conflict = state.kind === 'foreign-live' || state.kind === 'foreign-expired'
    ? state.conflict
    : null;

  return { conflict, reclaimBusy, acquireForEdit, recordConflict, reclaim };
}
