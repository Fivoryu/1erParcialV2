import { useEffect, useState, type FormEvent } from 'react';
import { ApiTransportError } from '../api/client';
import type { Uuid } from '../api/types';
import {
  createVcsApiClient,
  vcsErrorMessage,
  type VcsBranch,
  type VcsMergeConflict,
  type VcsMergeRequest,
} from './api';
import { mergeRequestStateLabel } from './presentation';

const api = createVcsApiClient();

function conflictKindLabel(kind: VcsMergeConflict['kind']): string {
  switch (kind) {
    case 'FIELD_CONFLICT':
      return 'Conflicto de campo';
    case 'DELETE_MODIFY':
      return 'Conflicto de eliminación y modificación';
    case 'NAME_COLLISION':
      return 'Conflicto de nombres';
  }
}

function conflictValueLabel(value: unknown): string {
  if (value === null || value === undefined) {
    return '—';
  }
  if (typeof value === 'string') {
    return value.length === 0 ? '""' : value;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  try {
    return JSON.stringify(value) ?? '—';
  } catch {
    return String(value);
  }
}

export function mergeRequestErrorMessage(error: unknown, conflictsRefreshed = false): string {
  if (error instanceof ApiTransportError && error.code === 'MERGE_CONFLICTS') {
    return conflictsRefreshed
      ? 'No se completó la fusión porque el servidor informó de conflictos. Se actualizó la solicitud de fusión; revisa los conflictos antes de volver a intentarlo.'
      : 'No se completó la fusión porque el servidor informó de conflictos. Vuelve a cargar la solicitud para revisar los conflictos más recientes antes de intentarlo de nuevo.';
  }
  return vcsErrorMessage(error, 'No se pudo completar la acción de la solicitud de fusión.');
}

export interface MergeRequestsPanelViewProps {
  branches: VcsBranch[];
  mergeRequests: VcsMergeRequest[];
  loading: boolean;
  busy: boolean;
  busyMergeRequestId: Uuid | null;
  error: string | null;
  notice: string | null;
  onOpen(sourceBranch: string, targetBranch: string, title: string): void;
  onMerge(mergeRequest: VcsMergeRequest): void;
  onClose(mergeRequest: VcsMergeRequest): void;
  onDismissError(): void;
}

export function MergeRequestsPanelView({
  branches,
  mergeRequests,
  loading,
  busy,
  busyMergeRequestId,
  error,
  notice,
  onOpen,
  onMerge,
  onClose,
  onDismissError,
}: MergeRequestsPanelViewProps) {
  const [sourceBranch, setSourceBranch] = useState('');
  const [targetBranch, setTargetBranch] = useState('');
  const [title, setTitle] = useState('');

  function submitMergeRequest(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    const trimmedTitle = title.trim();
    if (sourceBranch && targetBranch && sourceBranch !== targetBranch && trimmedTitle && !busy) {
      onOpen(sourceBranch, targetBranch, trimmedTitle);
    }
  }

  return (
    <section className="surface vcs-panel merge-requests-panel" aria-labelledby="vcs-merge-requests-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Versionado</p>
          <h2 id="vcs-merge-requests-title">Solicitudes de fusión</h2>
        </div>
        <span className="count-chip vcs-count-chip">{mergeRequests.length}</span>
      </div>

      {error && (
        <div className="notice notice-error" role="alert">
          <span>{error}</span>
          <button className="text-button" type="button" onClick={onDismissError}>Descartar</button>
        </div>
      )}
      {notice && <p className="generation-notice" role="status">{notice}</p>}

      <form className="vcs-merge-request-form" onSubmit={submitMergeRequest}>
        <label className="field-label">
          Rama de origen
          <select
            aria-label="Rama de origen"
            onChange={(event) => setSourceBranch(event.target.value)}
            required
            value={sourceBranch}
          >
            <option value="">Elige una rama de origen</option>
            {branches.map((branch) => <option key={branch.name} value={branch.name}>{branch.name}</option>)}
          </select>
        </label>
        <label className="field-label">
          Rama de destino
          <select
            aria-label="Rama de destino"
            onChange={(event) => setTargetBranch(event.target.value)}
            required
            value={targetBranch}
          >
            <option value="">Elige una rama de destino</option>
            {branches.map((branch) => <option key={branch.name} value={branch.name}>{branch.name}</option>)}
          </select>
        </label>
        <label className="field-label">
          Título
          <input
            autoComplete="off"
            onChange={(event) => setTitle(event.target.value)}
            placeholder="Describe esta revisión"
            required
            value={title}
          />
        </label>
        <button
          className="button button-primary"
          disabled={busy || loading || branches.length < 2 || sourceBranch === targetBranch}
          type="submit"
        >
          {busy && busyMergeRequestId === null ? 'Procesando…' : 'Abrir solicitud de fusión'}
        </button>
      </form>

      <div className="vcs-section">
        <p className="vcs-subsection-label">Solicitudes de revisión</p>
        {loading ? (
          <p className="quiet-state" role="status">Cargando las solicitudes de fusión…</p>
        ) : mergeRequests.length === 0 ? (
          <p className="quiet-state">Aún no hay solicitudes de fusión. Abre una para pedir la revisión de una rama.</p>
        ) : (
          <ul className="vcs-merge-request-list">
            {mergeRequests.map((mergeRequest) => {
              const requestBusy = busyMergeRequestId === mergeRequest.mergeRequestId;
              const isOpen = mergeRequest.state === 'OPEN';
              return (
                <li className="vcs-merge-request-row" key={mergeRequest.mergeRequestId}>
                  <div className="vcs-merge-request-copy">
                    <h4>{mergeRequest.title}</h4>
                    <p className="vcs-branch-pair"><strong>{mergeRequest.sourceBranch}</strong> → <strong>{mergeRequest.targetBranch}</strong></p>
                    <span className={`vcs-state-chip vcs-state-chip--${mergeRequest.state.toLowerCase()}`}>
                      {mergeRequestStateLabel(mergeRequest.state)}
                    </span>
                    <p className="property-hint">
                      Abierta por {mergeRequest.openedByDisplayName}
                      {mergeRequest.decidedByDisplayName ? ` · decidida por ${mergeRequest.decidedByDisplayName}` : ''}
                    </p>
                    {mergeRequest.hasConflicts && mergeRequest.conflicts.length === 0 && (
                      <p className="notice notice-error" role="status">
                        El servidor informa de conflictos sin resolver, pero no hay detalles disponibles.
                      </p>
                    )}
                    {mergeRequest.conflicts.length > 0 && (
                      <div className="vcs-conflict-section">
                        <h5>Conflictos ({mergeRequest.conflicts.length})</h5>
                        <ul className="vcs-conflict-list">
                          {mergeRequest.conflicts.map((conflict, index) => (
                            <li className="vcs-conflict-row" key={`${conflict.targetType}-${conflict.targetId}-${index}`}>
                              <h4>{conflictKindLabel(conflict.kind)} · {conflict.targetName}</h4>
                              <p>{conflict.message}</p>
                              <dl className="vcs-conflict-values">
                                <div><dt>Campo</dt><dd>{conflict.field ?? '—'}</dd></div>
                                <div><dt>Base</dt><dd>{conflictValueLabel(conflict.baseValue)}</dd></div>
                                <div><dt>Origen</dt><dd>{conflictValueLabel(conflict.sourceValue)}</dd></div>
                                <div><dt>Destino</dt><dd>{conflictValueLabel(conflict.targetValue)}</dd></div>
                              </dl>
                            </li>
                          ))}
                        </ul>
                      </div>
                    )}
                  </div>
                  <div className="vcs-merge-request-controls">
                    <button
                      className="button button-primary button-compact"
                      disabled={busy || !isOpen || mergeRequest.hasConflicts}
                      onClick={() => onMerge(mergeRequest)}
                      type="button"
                    >
                      {requestBusy ? 'Fusionando…' : 'Fusionar'}
                    </button>
                    <button
                      className="button button-secondary button-compact"
                      disabled={busy || !isOpen}
                      onClick={() => onClose(mergeRequest)}
                      type="button"
                    >
                      {requestBusy ? 'Procesando…' : 'Cerrar solicitud'}
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </section>
  );
}

export interface MergeRequestsPanelProps {
  projectId: Uuid;
}

export default function MergeRequestsPanel({ projectId }: MergeRequestsPanelProps) {
  const [branches, setBranches] = useState<VcsBranch[]>([]);
  const [mergeRequests, setMergeRequests] = useState<VcsMergeRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [busyMergeRequestId, setBusyMergeRequestId] = useState<Uuid | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    setBranches([]);
    setMergeRequests([]);
    void Promise.all([api.listBranches(projectId), api.listMergeRequests(projectId)])
      .then(([loadedBranches, loadedRequests]) => {
        if (active) {
          setBranches(loadedBranches);
          setMergeRequests(loadedRequests);
        }
      })
      .catch((cause: unknown) => {
        if (active) {
          setError(mergeRequestErrorMessage(cause));
        }
      })
      .finally(() => {
        if (active) {
          setLoading(false);
        }
      });
    return () => {
      active = false;
    };
  }, [projectId]);

  function replaceMergeRequest(updated: VcsMergeRequest): void {
    setMergeRequests((current) => [
      updated,
      ...current.filter((item) => item.mergeRequestId !== updated.mergeRequestId),
    ]);
  }

  async function handleOpen(sourceBranch: string, targetBranch: string, title: string): Promise<void> {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const opened = await api.createMergeRequest(projectId, { sourceBranch, targetBranch, title });
      replaceMergeRequest(opened);
      setNotice('Se abrió la solicitud de fusión.');
    } catch (cause: unknown) {
      setError(mergeRequestErrorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  async function handleMerge(mergeRequest: VcsMergeRequest): Promise<void> {
    setBusy(true);
    setBusyMergeRequestId(mergeRequest.mergeRequestId);
    setError(null);
    setNotice(null);
    try {
      const merged = await api.mergeMergeRequest(projectId, mergeRequest.mergeRequestId);
      replaceMergeRequest(merged);
      setNotice(`Se fusionó la solicitud “${mergeRequest.title}”.`);
    } catch (cause: unknown) {
      let conflictsRefreshed = false;
      if (cause instanceof ApiTransportError && cause.code === 'MERGE_CONFLICTS') {
        try {
          replaceMergeRequest(await api.getMergeRequest(projectId, mergeRequest.mergeRequestId));
          conflictsRefreshed = true;
        } catch {
          // Keep the failed merge explicit even if the follow-up read cannot refresh the conflict details.
        }
      }
      setError(mergeRequestErrorMessage(cause, conflictsRefreshed));
    } finally {
      setBusy(false);
      setBusyMergeRequestId(null);
    }
  }

  async function handleClose(mergeRequest: VcsMergeRequest): Promise<void> {
    setBusy(true);
    setBusyMergeRequestId(mergeRequest.mergeRequestId);
    setError(null);
    setNotice(null);
    try {
      const closed = await api.closeMergeRequest(projectId, mergeRequest.mergeRequestId);
      replaceMergeRequest(closed);
      setNotice(`Se cerró la solicitud de fusión “${mergeRequest.title}”.`);
    } catch (cause: unknown) {
      setError(mergeRequestErrorMessage(cause));
    } finally {
      setBusy(false);
      setBusyMergeRequestId(null);
    }
  }

  return (
    <MergeRequestsPanelView
      branches={branches}
      mergeRequests={mergeRequests}
      loading={loading}
      busy={busy}
      busyMergeRequestId={busyMergeRequestId}
      error={error}
      notice={notice}
      onOpen={(sourceBranch, targetBranch, title) => void handleOpen(sourceBranch, targetBranch, title)}
      onMerge={(mergeRequest) => void handleMerge(mergeRequest)}
      onClose={(mergeRequest) => void handleClose(mergeRequest)}
      onDismissError={() => setError(null)}
    />
  );
}
