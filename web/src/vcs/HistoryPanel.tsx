import { useEffect, useState, type FormEvent } from 'react';
import { commitChangeLabel, commitKindLabel, commitOriginLabel, relativeTimeLabel } from './presentation';
import Modal from '../ui/Modal';
import type { Uuid } from '../api/types';
import {
  createVcsApiClient,
  vcsErrorMessage,
  type VcsBranch,
  type VcsCommit,
  type VcsCommitDetail,
  type VcsStatus,
} from './api';

const api = createVcsApiClient();

export { relativeTimeLabel } from './presentation';

export interface HistoryPanelViewProps {
  branches: VcsBranch[];
  status: VcsStatus | null;
  selectedBranch: string;
  commits: VcsCommit[];
  commitDetail: VcsCommitDetail | null;
  loading: boolean;
  detailLoading: boolean;
  busy: boolean;
  error: string | null;
  notice: string | null;
  onSelectBranch(branch: string): void;
  onCommit(message: string): void;
  onOpenCommit(commitId: Uuid): void;
  onCloseDetail(): void;
  onDismissError(): void;
}

export function HistoryPanelView({
  branches,
  status,
  selectedBranch,
  commits,
  commitDetail,
  loading,
  detailLoading,
  busy,
  error,
  notice,
  onSelectBranch,
  onCommit,
  onOpenCommit,
  onCloseDetail,
  onDismissError,
}: HistoryPanelViewProps) {
  const [message, setMessage] = useState('');

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

      <Modal open={detailLoading || commitDetail !== null} title="Detalles de la confirmación" size="md" onClose={onCloseDetail}>
        {detailLoading ? (
          <p className="quiet-state" role="status">Cargando los elementos modificados…</p>
        ) : commitDetail && (
          <div className="vcs-commit-detail">
            <p className="eyebrow">{commitDetail.authorDisplayName} · {relativeTimeLabel(commitDetail.createdAt)}</p>
            <h3>{commitDetail.message}</h3>
            <h4>Elementos modificados</h4>
            {commitDetail.changes.length === 0 ? (
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
}

export default function HistoryPanel({ projectId, onStatusChanged, onDetailOpenChange }: HistoryPanelProps) {
  const [branches, setBranches] = useState<VcsBranch[]>([]);
  const [status, setStatus] = useState<VcsStatus | null>(null);
  const [selectedBranch, setSelectedBranch] = useState('');
  const [commits, setCommits] = useState<VcsCommit[]>([]);
  const [commitDetail, setCommitDetail] = useState<VcsCommitDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    setStatus(null);
    setBranches([]);
    setCommits([]);
    setSelectedBranch('');
    setCommitDetail(null);

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

  async function handleOpenCommit(commitId: Uuid): Promise<void> {
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
      onCloseDetail={() => {
        setCommitDetail(null);
        setDetailLoading(false);
        onDetailOpenChange?.(false);
      }}
      onDismissError={() => setError(null)}
    />
  );
}
