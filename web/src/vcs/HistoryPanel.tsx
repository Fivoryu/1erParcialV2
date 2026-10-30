import { useEffect, useState, type FormEvent } from 'react';
import { commitChangeLabel, commitKindLabel, commitOriginLabel, relativeTimeLabel } from './presentation';
import Modal from '../ui/Modal';
import { AuthorsList, DiffView } from './DiffView';
import { ApiTransportError, createApiClient } from '../api/client';
import type { ModelResource, Uuid } from '../api/types';
import {
  createVcsApiClient,
  vcsErrorMessage,
  type VcsBranch,
  type VcsCommit,
  type VcsCommitComparison,
  type VcsCommitDetail,
  type VcsStatus,
} from './api';

const api = createVcsApiClient();
const modelApi = createApiClient();

export type CommitRestoreAttempt =
  | { kind: 'restored'; commit: VcsCommit; model: ModelResource }
  | { kind: 'error'; message: string };

/** Restores a commit, then reads the model the restore left in the editor; failures become a readable message. */
export async function attemptCommitRestore(
  restore: () => Promise<VcsCommit>,
  loadModel: () => Promise<ModelResource>,
): Promise<CommitRestoreAttempt> {
  try {
    const commit = await restore();
    return { kind: 'restored', commit, model: await loadModel() };
  } catch (cause: unknown) {
    if (cause instanceof ApiTransportError && cause.code === 'WORKING_COPY_CLEAN') {
      return { kind: 'error', message: 'La rama ya tiene el diagrama de esta versión; no hay nada que restaurar.' };
    }
    return { kind: 'error', message: vcsErrorMessage(cause, 'No se pudo restaurar la versión.') };
  }
}

export { relativeTimeLabel } from './presentation';

/** A comparison of two commits while it loads, after it failed or once it is loaded. */
export interface HistoryComparisonState {
  loading: boolean;
  error: string | null;
  result: VcsCommitComparison | null;
}

export interface HistoryPanelViewProps {
  branches: VcsBranch[];
  status: VcsStatus | null;
  selectedBranch: string;
  commits: VcsCommit[];
  commitDetail: VcsCommitDetail | null;
  /** The open comparison, or null/undefined while none is shown. */
  comparison?: HistoryComparisonState | null;
  loading: boolean;
  detailLoading: boolean;
  busy: boolean;
  error: string | null;
  notice: string | null;
  onSelectBranch(branch: string): void;
  onCommit(message: string): void;
  onOpenCommit(commitId: Uuid): void;
  onCompare?(fromCommitId: Uuid, toCommitId: Uuid): void;
  onCloseComparison?(): void;
  onCloseDetail(): void;
  onDismissError(): void;
  /** Whether the restore confirmation of the open commit is showing. */
  restoreConfirming?: boolean;
  onRequestRestore?(): void;
  onCancelRestore?(): void;
  onConfirmRestore?(commitId: Uuid): void;
}

export function HistoryPanelView({
  branches,
  status,
  selectedBranch,
  commits,
  commitDetail,
  comparison = null,
  loading,
  detailLoading,
  busy,
  error,
  notice,
  onSelectBranch,
  onCommit,
  onOpenCommit,
  onCompare,
  onCloseComparison,
  onCloseDetail,
  onDismissError,
  restoreConfirming = false,
  onRequestRestore,
  onCancelRestore,
  onConfirmRestore,
}: HistoryPanelViewProps) {
  const [message, setMessage] = useState('');
  const [compareFrom, setCompareFrom] = useState('');
  const [compareTo, setCompareTo] = useState('');

  function submitCompare(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    if (compareFrom && compareTo && compareFrom !== compareTo && !busy) {
      onCompare?.(compareFrom, compareTo);
    }
  }

  function submitCommit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    const trimmed = message.trim();
    if (trimmed.length > 0 && !busy) {
      onCommit(trimmed);
    }
  }

  return (
    <section className="surface vcs-panel history-panel" aria-labelledby="vcs-history-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Versionado</p>
          <h2 id="vcs-history-title">Historial</h2>
        </div>
        <span className="count-chip vcs-count-chip">{commits.length}</span>
      </div>

      {error && (
        <div className="notice notice-error" role="alert">
          <span>{error}</span>
          <button className="text-button" type="button" onClick={onDismissError}>Descartar</button>
        </div>
      )}
      {notice && <p className="generation-notice" role="status">{notice}</p>}

      <div className="vcs-history-controls">
        <label className="field-label">
          Historial de ramas
          <select
            aria-label="Rama del historial"
            disabled={loading || branches.length === 0}
            onChange={(event) => onSelectBranch(event.target.value)}
            value={selectedBranch}
          >
            {branches.map((branch) => <option key={branch.name} value={branch.name}>{branch.name}</option>)}
          </select>
        </label>
        {status?.dirty === false && <span className="vcs-clean-chip" role="status">Copia de trabajo limpia</span>}
      </div>

      <form className="vcs-commit-form" onSubmit={submitCommit}>
        <label className="field-label">
          Mensaje de confirmación
          <input
            autoComplete="off"
            onChange={(event) => setMessage(event.target.value)}
            placeholder="Describe este cambio"
            required
            value={message}
          />
        </label>
        <button className="button button-primary" disabled={busy || loading} type="submit">
          {busy ? 'Guardando…' : 'Confirmar cambios'}
        </button>
      </form>

      <div className="vcs-section">
        <p className="vcs-subsection-label">Cambios en {selectedBranch || 'la rama actual'}</p>
        {loading ? (
          <p className="quiet-state" role="status">Cargando el historial…</p>
        ) : commits.length === 0 ? (
          <p className="quiet-state">Aún no hay confirmaciones. Haz un cambio y guárdalo para iniciar el historial.</p>
        ) : (
          <ul className="vcs-commit-list">
            {commits.map((commit) => (
              <li className="vcs-commit-row" key={commit.commitId}>
                <button
                  className="vcs-commit-open"
                  type="button"
                  onClick={() => onOpenCommit(commit.commitId)}
                  aria-label={`Ver cambio de ${commit.authorDisplayName}: ${commit.message}`}
                >
                  <strong>{commit.message}</strong>
                  <span>{commit.authorDisplayName} · {relativeTimeLabel(commit.createdAt)}</span>
                </button>
                <div className="vcs-commit-chips">
                <span className="vcs-kind-chip">{commitKindLabel(commit.kind)}</span>
                <span className="vcs-origin-chip">{commitOriginLabel(commit.origin)}</span>
              </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      {commits.length >= 2 && (
        <form className="vcs-compare-form" onSubmit={submitCompare}>
          <p className="vcs-subsection-label">Comparar confirmaciones</p>
          <div className="vcs-compare-fields">
            <label className="field-label">
              Desde
              <select aria-label="Confirmación inicial" onChange={(event) => setCompareFrom(event.target.value)} value={compareFrom}>
                <option value="">Elige la confirmación inicial</option>
                {commits.map((commit) => (
                  <option key={commit.commitId} value={commit.commitId}>{commit.message} · {commit.authorDisplayName}</option>
                ))}
              </select>
            </label>
            <label className="field-label">
              Hasta
              <select aria-label="Confirmación final" onChange={(event) => setCompareTo(event.target.value)} value={compareTo}>
                <option value="">Elige la confirmación final</option>
                {commits.map((commit) => (
                  <option key={commit.commitId} value={commit.commitId}>{commit.message} · {commit.authorDisplayName}</option>
                ))}
              </select>
            </label>
          </div>
          <button
            className="button button-secondary"
            disabled={busy || !compareFrom || !compareTo || compareFrom === compareTo}
            type="submit"
          >
            Comparar
          </button>
        </form>
      )}

      <Modal
        open={comparison !== null}
        title="Comparación de confirmaciones"
        size="md"
        onClose={() => onCloseComparison?.()}
      >
        {comparison?.loading ? (
          <p className="quiet-state" role="status">Comparando las confirmaciones…</p>
        ) : comparison?.error ? (
          <p className="notice notice-error" role="alert">{comparison.error}</p>
        ) : comparison?.result && (
          <div className="vcs-commit-detail">
            <h4>Confirmaciones ({comparison.result.commits.length})</h4>
            <ul className="vcs-merge-request-commits">
              {comparison.result.commits.map((commit) => (
                <li key={commit.commitId}>
                  <strong>{commit.message}</strong>
                  <span>{commit.authorDisplayName} · {relativeTimeLabel(commit.createdAt)}</span>
                </li>
              ))}
            </ul>
            {comparison.result.contributors.length > 0 && <h4>Participantes</h4>}
            <AuthorsList authors={comparison.result.contributors} />
            <h4>Cambios</h4>
            <DiffView changes={comparison.result.changes} />
          </div>
        )}
      </Modal>

      <Modal open={detailLoading || commitDetail !== null} title="Detalles de la confirmación" size="md" onClose={onCloseDetail}>
        {detailLoading ? (
          <p className="quiet-state" role="status">Cargando los elementos modificados…</p>
        ) : commitDetail && (
          <div className="vcs-commit-detail">
            <p className="eyebrow">{commitDetail.authorDisplayName} · {relativeTimeLabel(commitDetail.createdAt)}</p>
            <h3>{commitDetail.message}</h3>
            {(commitDetail.authors ?? []).length > 0 && <h4>Autores</h4>}
            <AuthorsList authors={commitDetail.authors ?? []} />
            <h4>Elementos modificados</h4>
            {(commitDetail.details ?? []).length > 0 ? (
              <DiffView changes={commitDetail.details ?? []} />
            ) : commitDetail.changes.length === 0 ? (
              <p className="quiet-state">No se enumeran cambios en los elementos. Selecciona otra confirmación para revisar sus cambios.</p>
            ) : (
              <ul className="vcs-change-list">
                {commitDetail.changes.map((change, index) => (
                  <li key={`${change.targetType}-${change.targetId}-${index}`}>
                    <strong>{change.targetName}</strong>
                    <span>{commitChangeLabel(change.changeKind)}</span>
                  </li>
                ))}
              </ul>
            )}
            {restoreConfirming ? (
              <div className="vcs-restore-confirm" role="alertdialog" aria-label="Confirmar restauración">
                <p>
                  Se creará una confirmación nueva en {status?.branch ?? 'la rama actual'} con el diagrama de esta
                  versión. El historial no se modifica.
                </p>
                <div className="vcs-restore-actions">
                  <button
                    className="button button-primary"
                    disabled={busy}
                    type="button"
                    onClick={() => onConfirmRestore?.(commitDetail.commitId)}
                  >
                    {busy ? 'Restaurando…' : 'Confirmar restauración'}
                  </button>
                  <button className="button button-secondary" disabled={busy} type="button" onClick={() => onCancelRestore?.()}>
                    Cancelar
                  </button>
                </div>
              </div>
            ) : (
              <div className="vcs-restore-actions">
                <button className="button button-secondary" disabled={busy} type="button" onClick={() => onRequestRestore?.()}>
                  Restaurar esta versión
                </button>
              </div>
            )}
          </div>
        )}
      </Modal>
    </section>
  );
}

export interface HistoryPanelProps {
  projectId: Uuid;
  onStatusChanged?(): void;
  onDetailOpenChange?(open: boolean): void;
  /** Receives the editor model after a commit was restored, like a branch checkout does. */
  onModelRestored?(model: ModelResource): void;
}

export default function HistoryPanel({
  projectId,
  onStatusChanged,
  onDetailOpenChange,
  onModelRestored,
}: HistoryPanelProps) {
  const [branches, setBranches] = useState<VcsBranch[]>([]);
  const [status, setStatus] = useState<VcsStatus | null>(null);
  const [selectedBranch, setSelectedBranch] = useState('');
  const [commits, setCommits] = useState<VcsCommit[]>([]);
  const [commitDetail, setCommitDetail] = useState<VcsCommitDetail | null>(null);
  const [comparison, setComparison] = useState<HistoryComparisonState | null>(null);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [restoreConfirming, setRestoreConfirming] = useState(false);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    setStatus(null);
    setBranches([]);
    setCommits([]);
    setSelectedBranch('');
    setCommitDetail(null);
    setComparison(null);

    void Promise.all([api.getStatus(projectId), api.listBranches(projectId)])
      .then(([loadedStatus, loadedBranches]) => {
        if (!active) {
          return;
        }
        setStatus(loadedStatus);
        setBranches(loadedBranches);
        setSelectedBranch(loadedStatus.branch);
      })
      .catch((cause: unknown) => {
        if (active) {
          setError(vcsErrorMessage(cause, 'No se pudo cargar el historial de versiones.'));
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

  useEffect(() => {
    if (!selectedBranch) {
      return undefined;
    }
    let active = true;
    setLoading(true);
    void api.listCommits(projectId, selectedBranch)
      .then((loadedCommits) => {
        if (active) {
          setCommits(loadedCommits);
        }
      })
      .catch((cause: unknown) => {
        if (active) {
          setError(vcsErrorMessage(cause, 'No se pudo cargar el historial de versiones.'));
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
  }, [projectId, selectedBranch]);

  async function handleCommit(message: string): Promise<void> {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await api.createCommit(projectId, { message });
      onStatusChanged?.();
      const [nextStatus, nextBranches] = await Promise.all([
        api.getStatus(projectId),
        api.listBranches(projectId),
      ]);
      setStatus(nextStatus);
      setBranches(nextBranches);
      setSelectedBranch(nextStatus.branch);
      setNotice('El cambio se agregó al historial.');
    } catch (cause: unknown) {
      setError(vcsErrorMessage(cause, 'No se pudo confirmar el cambio.'));
    } finally {
      setBusy(false);
    }
  }

  async function handleRestore(commitId: Uuid): Promise<void> {
    setBusy(true);
    setError(null);
    setNotice(null);
    const outcome = await attemptCommitRestore(
      () => api.restoreCommit(projectId, commitId),
      () => modelApi.getModel(projectId),
    );
    if (outcome.kind === 'error') {
      setError(outcome.message);
      setRestoreConfirming(false);
      setCommitDetail(null);
      onDetailOpenChange?.(false);
      setBusy(false);
      return;
    }
    try {
      const [nextStatus, nextBranches] = await Promise.all([
        api.getStatus(projectId),
        api.listBranches(projectId),
      ]);
      setStatus(nextStatus);
      setBranches(nextBranches);
      setSelectedBranch(nextStatus.branch);
      setCommits(await api.listCommits(projectId, nextStatus.branch));
    } catch (cause: unknown) {
      setError(vcsErrorMessage(cause, 'No se pudo actualizar el historial de versiones.'));
    }
    onModelRestored?.(outcome.model);
    onStatusChanged?.();
    setRestoreConfirming(false);
    setCommitDetail(null);
    onDetailOpenChange?.(false);
    setNotice('Se restauró la versión como una confirmación nueva en el historial.');
    setBusy(false);
  }

  async function handleCompare(fromCommitId: Uuid, toCommitId: Uuid): Promise<void> {
    setComparison({ loading: true, error: null, result: null });
    onDetailOpenChange?.(true);
    try {
      const result = await api.compareCommits(projectId, fromCommitId, toCommitId);
      setComparison({ loading: false, error: null, result });
    } catch (cause: unknown) {
      setComparison({
        loading: false,
        error: vcsErrorMessage(cause, 'No se pudieron comparar las confirmaciones.'),
        result: null,
      });
    }
  }

  function handleCloseComparison(): void {
    setComparison(null);
    onDetailOpenChange?.(false);
  }

  async function handleOpenCommit(commitId: Uuid): Promise<void> {
    setRestoreConfirming(false);
    setCommitDetail(null);
    setDetailLoading(true);
    onDetailOpenChange?.(true);
    setError(null);
    try {
      setCommitDetail(await api.getCommit(projectId, commitId));
    } catch (cause: unknown) {
      setError(vcsErrorMessage(cause, 'No se pudieron cargar los detalles de la confirmación.'));
      setDetailLoading(false);
      onDetailOpenChange?.(false);
    } finally {
      setDetailLoading(false);
    }
  }

  return (
    <HistoryPanelView
      branches={branches}
      status={status}
      selectedBranch={selectedBranch}
      commits={commits}
      commitDetail={commitDetail}
      comparison={comparison}
      loading={loading}
      detailLoading={detailLoading}
      busy={busy}
      error={error}
      notice={notice}
      onSelectBranch={(branch) => {
        setSelectedBranch(branch);
        setError(null);
      }}
      onCommit={(message) => void handleCommit(message)}
      onOpenCommit={(commitId) => void handleOpenCommit(commitId)}
      onCompare={(fromCommitId, toCommitId) => void handleCompare(fromCommitId, toCommitId)}
      onCloseComparison={handleCloseComparison}
      onCloseDetail={() => {
        setRestoreConfirming(false);
        setCommitDetail(null);
        setDetailLoading(false);
        onDetailOpenChange?.(false);
      }}
      onDismissError={() => setError(null)}
      restoreConfirming={restoreConfirming}
      onRequestRestore={() => setRestoreConfirming(true)}
      onCancelRestore={() => setRestoreConfirming(false)}
      onConfirmRestore={(commitId) => void handleRestore(commitId)}
    />
  );
}
