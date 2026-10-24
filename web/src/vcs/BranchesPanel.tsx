import { useEffect, useState, type FormEvent } from 'react';
import type { ModelResource, Uuid } from '../api/types';
import {
  createVcsApiClient,
  vcsErrorMessage,
  type VcsBranch,
  type VcsCheckoutResource,
  type VcsStatus,
} from './api';
import { relativeTimeLabel } from './presentation';

const api = createVcsApiClient();

export type BranchCheckoutAttempt =
  | { kind: 'checked-out'; checkout: VcsCheckoutResource }
  | { kind: 'error'; currentBranch: string; message: string };

export async function attemptBranchCheckout(
  checkout: () => Promise<VcsCheckoutResource>,
  currentBranch: string,
): Promise<BranchCheckoutAttempt> {
  try {
    return { kind: 'checked-out', checkout: await checkout() };
  } catch (cause: unknown) {
    return {
      kind: 'error',
      currentBranch,
      message: vcsErrorMessage(cause, 'The branch could not be switched.'),
    };
  }
}

export interface BranchesPanelViewProps {
  branches: VcsBranch[];
  status: VcsStatus | null;
  loading: boolean;
  busy: boolean;
  busyBranch: string | null;
  error: string | null;
  notice: string | null;
  onCreateBranch(name: string, fromBranch?: string): void;
  onCheckout(branchName: string): void;
  onToggleProtection(branch: VcsBranch): void;
  onDismissError(): void;
}

export function BranchesPanelView({
  branches,
  status,
  loading,
  busy,
  busyBranch,
  error,
  notice,
  onCreateBranch,
  onCheckout,
  onToggleProtection,
  onDismissError,
}: BranchesPanelViewProps) {
  const [name, setName] = useState('');
  const [fromBranch, setFromBranch] = useState('');

  function submitBranch(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    const trimmed = name.trim();
    if (trimmed.length > 0 && !busy) {
      onCreateBranch(trimmed, fromBranch || undefined);
    }
  }

  return (
    <section className="surface vcs-panel branches-panel" aria-labelledby="vcs-branches-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Versioning</p>
          <h2 id="vcs-branches-title">Branches</h2>
        </div>
        <span className="count-chip vcs-count-chip">{branches.length}</span>
      </div>

      {error && (
        <div className="notice notice-error" role="alert">
          <span>{error}</span>
          <button className="text-button" type="button" onClick={onDismissError}>Dismiss</button>
        </div>
      )}
      {notice && <p className="generation-notice" role="status">{notice}</p>}
      <form className="vcs-branch-form" onSubmit={submitBranch}>
        <label className="field-label">
          New branch name
          <input
            autoComplete="off"
            onChange={(event) => setName(event.target.value)}
            placeholder="feature-name"
            required
            value={name}
          />
        </label>
        <label className="field-label">
          Start from
          <select
            aria-label="Starting branch"
            onChange={(event) => setFromBranch(event.target.value)}
            value={fromBranch}
          >
            <option value="">Current branch{status ? ` (${status.branch})` : ''}</option>
            {branches.map((branch) => <option key={branch.name} value={branch.name}>{branch.name}</option>)}
          </select>
        </label>
        <button className="button button-primary" disabled={busy || loading} type="submit">
          {busy && busyBranch === null ? 'Working…' : 'Create branch'}
        </button>
      </form>

      {loading ? (
        <p className="quiet-state" role="status">Loading branches…</p>
      ) : branches.length === 0 ? (
        <p className="quiet-state">No branches are available; create a branch to start working.</p>
      ) : (
        <ul className="vcs-branch-list">
          {branches.map((branch) => {
            const current = branch.name === status?.branch;
            return (
              <li className={`vcs-branch-row${current ? ' vcs-branch-row--current' : ''}`} key={branch.name}>
                <div className="vcs-branch-copy">
                  <div className="vcs-branch-heading">
                    <h3>{branch.name}</h3>
                    {current && <span className="vcs-current-chip">Current</span>}
                  </div>
                  <p className="vcs-branch-meta">
                    <strong>Head:</strong> {branch.headMessage ?? 'No commits yet.'}
                    {branch.headAuthorDisplayName && ` · ${branch.headAuthorDisplayName}`}
                    {branch.headCreatedAt && ` · ${relativeTimeLabel(branch.headCreatedAt)}`}
                  </p>
                </div>
                <div className="vcs-branch-controls">
                  <label className={`vcs-protection-chip${branch.isProtected ? ' is-protected' : ' is-unprotected'}`}>
                    <input
                      type="checkbox"
                      checked={branch.isProtected}
                      disabled={busy}
                      aria-label={`Protect ${branch.name}`}
                      onChange={() => onToggleProtection(branch)}
                    />
                    <span>{branch.isProtected ? 'Protected' : 'Not protected'}</span>
                  </label>
                  <button
                    className="button button-secondary button-compact"
                    type="button"
                    disabled={busy || loading || current}
                    onClick={() => onCheckout(branch.name)}
                  >
                    {busyBranch === branch.name ? 'Switching…' : current ? 'Current branch' : 'Switch branch'}
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

export interface BranchesPanelProps {
  projectId: Uuid;
  onModelCheckout(model: ModelResource): void;
  onStatusChanged?(): void;
}

export default function BranchesPanel({ projectId, onModelCheckout, onStatusChanged }: BranchesPanelProps) {
  const [branches, setBranches] = useState<VcsBranch[]>([]);
  const [status, setStatus] = useState<VcsStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [busyBranch, setBusyBranch] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    setStatus(null);
    setBranches([]);
    void Promise.all([api.getStatus(projectId), api.listBranches(projectId)])
      .then(([nextStatus, nextBranches]) => {
        if (active) {
          setStatus(nextStatus);
          setBranches(nextBranches);
        }
      })
      .catch((cause: unknown) => {
        if (active) {
          setError(vcsErrorMessage(cause, 'The branches could not be loaded.'));
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

  async function handleCreateBranch(name: string, fromBranch?: string): Promise<void> {
    setBusy(true);
    setBusyBranch(null);
    setError(null);
    setNotice(null);
    try {
      const created = await api.createBranch(projectId, {
        name,
        ...(fromBranch ? { fromBranch } : {}),
      });
      setBranches((current) => [created, ...current.filter((branch) => branch.name !== created.name)]);
      setNotice(`Branch ${created.name} was created.`);
    } catch (cause: unknown) {
      setError(vcsErrorMessage(cause, 'The branch could not be created.'));
    } finally {
      setBusy(false);
    }
  }

  async function handleCheckout(branchName: string): Promise<void> {
    setBusy(true);
    setBusyBranch(branchName);
    setError(null);
    setNotice(null);
    const outcome = await attemptBranchCheckout(
      () => api.checkoutBranch(projectId, branchName),
      status?.branch ?? '',
    );
    if (outcome.kind === 'error') {
      setError(outcome.message);
      setBusy(false);
      setBusyBranch(null);
      return;
    }

    setStatus((current) => current === null ? current : {
      ...current,
      branch: outcome.checkout.branch,
      headCommitId: branches.find((branch) => branch.name === outcome.checkout.branch)?.headCommitId ?? null,
      dirty: false,
    });
    onModelCheckout(outcome.checkout.model);
    onStatusChanged?.();
    setNotice(`Switched to ${outcome.checkout.branch}.`);
    setBusy(false);
    setBusyBranch(null);
  }

  async function handleProtectionToggle(branch: VcsBranch): Promise<void> {
    setBusy(true);
    setBusyBranch(branch.name);
    setError(null);
    setNotice(null);
    try {
      const updated = await api.updateBranchProtection(projectId, branch.name, !branch.isProtected);
      setBranches((current) => current.map((item) => item.name === updated.name ? updated : item));
    } catch (cause: unknown) {
      setError(vcsErrorMessage(cause, 'Branch protection could not be updated.'));
    } finally {
      setBusy(false);
      setBusyBranch(null);
    }
  }

  return (
    <BranchesPanelView
      branches={branches}
      status={status}
      loading={loading}
      busy={busy}
      busyBranch={busyBranch}
      error={error}
      notice={notice}
      onCreateBranch={(name, fromBranch) => void handleCreateBranch(name, fromBranch)}
      onCheckout={(branchName) => void handleCheckout(branchName)}
      onToggleProtection={(branch) => void handleProtectionToggle(branch)}
      onDismissError={() => setError(null)}
    />
  );
}
