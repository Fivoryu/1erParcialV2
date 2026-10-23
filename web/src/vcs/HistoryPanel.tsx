import { useEffect, useState, type FormEvent } from 'react';
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

export function relativeTimeLabel(timestamp: string, now = Date.now()): string {
  const createdAt = Date.parse(timestamp);
  if (Number.isNaN(createdAt)) {
    return 'Time unavailable';
  }

  const elapsedSeconds = (now - createdAt) / 1000;
  const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ['year', 60 * 60 * 24 * 365],
    ['month', 60 * 60 * 24 * 30],
    ['week', 60 * 60 * 24 * 7],
    ['day', 60 * 60 * 24],
    ['hour', 60 * 60],
    ['minute', 60],
    ['second', 1],
  ];
  const formatter = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });
  const [unit, secondsPerUnit] = units.find(([, seconds]) => Math.abs(elapsedSeconds) >= seconds) ?? units.at(-1)!;
  return formatter.format(-Math.round(elapsedSeconds / secondsPerUnit), unit);
}

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
        {status && <p className="property-hint">Current branch: <strong>{status.branch}</strong></p>}
      </div>

      {status?.dirty === false && <p className="quiet-state" role="status">nothing to commit</p>}

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
        <h3>Changes on {selectedBranch || 'the current branch'}</h3>
        {loading ? (
          <p className="quiet-state" role="status">Loading history…</p>
        ) : commits.length === 0 ? (
          <p className="quiet-state">No commits yet.</p>
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
                <span className="count-chip vcs-kind-chip">{commit.kind} · {commit.origin}</span>
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
              <p className="quiet-state">No element changes are listed.</p>
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
}

export default function HistoryPanel({ projectId }: HistoryPanelProps) {
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
    setError(null);
    try {
      setCommitDetail(await api.getCommit(projectId, commitId));
    } catch (cause: unknown) {
      setError(vcsErrorMessage(cause, 'The commit details could not be loaded.'));
      setDetailLoading(false);
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
      }}
      onDismissError={() => setError(null)}
    />
  );
}
