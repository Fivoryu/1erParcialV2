import { useEffect, useState, type FormEvent } from 'react';
import { relativeTimeLabel } from './presentation';
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
          <p className="eyebrow">Versioning</p>
          <h2 id="vcs-history-title">History</h2>
        </div>
        <span className="count-chip vcs-count-chip">{commits.length}</span>
      </div>

      {error && (
        <div className="notice notice-error" role="alert">
          <span>{error}</span>
          <button className="text-button" type="button" onClick={onDismissError}>Dismiss</button>
        </div>
      )}
      {notice && <p className="generation-notice" role="status">{notice}</p>}

      <div className="vcs-history-controls">
        <label className="field-label">
          Branch history
          <select
            aria-label="History branch"
            disabled={loading || branches.length === 0}
            onChange={(event) => onSelectBranch(event.target.value)}
            value={selectedBranch}
          >
            {branches.map((branch) => <option key={branch.name} value={branch.name}>{branch.name}</option>)}
          </select>
        </label>
        {status?.dirty === false && <span className="vcs-clean-chip" role="status">Clean working copy</span>}
      </div>

      <form className="vcs-commit-form" onSubmit={submitCommit}>
        <label className="field-label">
          Commit message
          <input
            autoComplete="off"
            onChange={(event) => setMessage(event.target.value)}
            placeholder="Describe this change"
            required
            value={message}
          />
        </label>
        <button className="button button-primary" disabled={busy || loading} type="submit">
          {busy ? 'Saving…' : 'Commit changes'}
        </button>
      </form>

      <div className="vcs-section">
        <p className="vcs-subsection-label">Changes on {selectedBranch || 'the current branch'}</p>
        {loading ? (
          <p className="quiet-state" role="status">Loading history…</p>
        ) : commits.length === 0 ? (
          <p className="quiet-state">No commits yet; make a change and commit it to start a history.</p>
        ) : (
          <ul className="vcs-commit-list">
            {commits.map((commit) => (
              <li className="vcs-commit-row" key={commit.commitId}>
                <button
                  className="vcs-commit-open"
                  type="button"
                  onClick={() => onOpenCommit(commit.commitId)}
                  aria-label={`View commit by ${commit.authorDisplayName}: ${commit.message}`}
                >
                  <strong>{commit.message}</strong>
                  <span>{commit.authorDisplayName} · {relativeTimeLabel(commit.createdAt)}</span>
                </button>
                <div className="vcs-commit-chips">
                <span className="vcs-kind-chip">{commit.kind}</span>
                <span className="vcs-origin-chip">{commit.origin}</span>
              </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      <Modal open={detailLoading || commitDetail !== null} title="Commit details" size="md" onClose={onCloseDetail}>
        {detailLoading ? (
          <p className="quiet-state" role="status">Loading changed elements…</p>
        ) : commitDetail && (
          <div className="vcs-commit-detail">
            <p className="eyebrow">{commitDetail.authorDisplayName} · {relativeTimeLabel(commitDetail.createdAt)}</p>
            <h3>{commitDetail.message}</h3>
            <h4>Changed elements</h4>
            {commitDetail.changes.length === 0 ? (
              <p className="quiet-state">No element changes are listed; select another commit to inspect its changes.</p>
            ) : (
              <ul className="vcs-change-list">
                {commitDetail.changes.map((change, index) => (
                  <li key={`${change.targetType}-${change.targetId}-${index}`}>
                    <strong>{change.targetName}</strong>
                    <span>{change.changeKind.replaceAll('_', ' ')}</span>
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
          setError(vcsErrorMessage(cause, 'The version history could not be loaded.'));
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
          setError(vcsErrorMessage(cause, 'The version history could not be loaded.'));
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
      setNotice('The change was added to history.');
    } catch (cause: unknown) {
      setError(vcsErrorMessage(cause, 'The change could not be committed.'));
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
      setError(vcsErrorMessage(cause, 'The commit details could not be loaded.'));
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
