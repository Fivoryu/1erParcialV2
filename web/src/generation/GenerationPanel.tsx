import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { ApiTransportError, createApiClient } from '../api/client';
import type {
  DeploymentResource,
  EditingLeaseConflict,
  GenerationProfileResource,
  GenerationRunResource,
  GenerationState,
  GenerationTargetResource,
  Uuid,
} from '../api/types';
import { mapEditingLeaseConflict } from '../collaboration/editingLease';
import EditingLeaseNotice from '../collaboration/EditingLeaseNotice';
import type { EditingLeaseAdapter } from '../collaboration/useEditingLease';
import {
  blockingDiagnostics,
  canDownloadArchive,
  canStopDeployment,
  describeDeploymentFailure,
  describeDeploymentState,
  describeRunState,
  editorIsLocal,
  isDeploymentActive,
  isDeploymentTerminal,
  isTerminalState,
  NO_AUTHENTICATION_WARNING,
  phoneBaseUrl,
  safeDeploymentDetail,
  suggestedTargetKey,
  targetOptionLabel,
  uniqueTargetKey,
} from './presentation';

const api = createApiClient();
const POLL_INTERVAL_MS = 1500;

function messageFor(error: unknown): string {
  if (error instanceof ApiTransportError) {
    return error.message;
  }
  return 'The generation request could not be completed. Check the connection and try again.';
}

/**
 * The product surface of the generation panel.
 *
 * It receives everything it shows as props so it renders without a backend, which is how the tests
 * prove the two inherited rules: several destinations are listed, and no destination, run, profile
 * or template identifier ever reaches the screen.
 */
export interface GenerationPanelViewProps {
  targets: GenerationTargetResource[];
  profiles: GenerationProfileResource[];
  selectedTargetId: string | null;
  selectedProfileId: string | null;
  run: GenerationRunResource | null;
  deployment: DeploymentResource | null;
  phoneUrl: string | null;
  editorOnLocalhost: boolean;
  busy: boolean;
  error: string | null;
  notice: string | null;
  editingLeaseConflict?: EditingLeaseConflict | null;
  reclaimBusy?: boolean;
  onReclaimLease?: () => void;
  onSelectTarget(targetId: string): void;
  onSelectProfile(profileId: string): void;
  onCreateTarget(name: string, databaseName: string): void;
  onGenerate(): void;
  onDownload(): void;
  onDeploy(): void;
  onStopDeployment(): void;
  onDismiss(): void;
}

export function GenerationPanelView({
  targets,
  profiles,
  selectedTargetId,
  selectedProfileId,
  run,
  deployment,
  phoneUrl,
  editorOnLocalhost,
  busy,
  error,
  notice,
  editingLeaseConflict = null,
  reclaimBusy = false,
  onReclaimLease,
  onSelectTarget,
  onSelectProfile,
  onCreateTarget,
  onGenerate,
  onDownload,
  onDeploy,
  onStopDeployment,
  onDismiss,
}: GenerationPanelViewProps) {
  const [formOpen, setFormOpen] = useState(false);
  const [targetName, setTargetName] = useState('');
  const [databaseName, setDatabaseName] = useState('');
  const fieldPrefix = useId();
  const nameFieldId = `${fieldPrefix}-target-name`;
  const databaseFieldId = `${fieldPrefix}-database-name`;

  const selectedProfile = profiles.find((profile) => profile.profileId === selectedProfileId) ?? null;
  const selectedTarget = targets.find((target) => target.targetId === selectedTargetId) ?? null;
  const diagnostics = blockingDiagnostics(run);
  const ready = canDownloadArchive(run);

  const submitTarget = useCallback((event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (targetName.trim().length === 0 || databaseName.trim().length === 0) {
      return;
    }
    onCreateTarget(targetName.trim(), databaseName.trim());
    setTargetName('');
    setDatabaseName('');
    setFormOpen(false);
  }, [databaseName, onCreateTarget, targetName]);

  return (
    <section className="generation-panel" aria-labelledby="generation-title">
      <div className="generation-heading">
        <div>
          <p className="eyebrow">Generation</p>
          <h2 id="generation-title">Generate a backend</h2>
        </div>
        <button
          className="text-button"
          type="button"
          onClick={() => setFormOpen((open) => !open)}
          aria-expanded={formOpen}
        >
          {formOpen ? 'Cancel new destination' : 'New destination'}
        </button>
      </div>

      {notice && (
        <p className="generation-notice" role="status">{notice}</p>
      )}

      {editingLeaseConflict !== null && (
        <EditingLeaseNotice
          conflict={editingLeaseConflict}
          onReclaim={onReclaimLease}
          busy={reclaimBusy}
        />
      )}

      {error && (
        <div className="notice notice-error" role="alert">
          <span>{error}</span>
          <button className="text-button" type="button" onClick={onDismiss}>Dismiss</button>
        </div>
      )}

      {formOpen && (
        <form className="generation-target-form" onSubmit={submitTarget}>
          <label htmlFor={nameFieldId}>Destination name</label>
          <input
            id={nameFieldId}
            value={targetName}
            onChange={(event) => setTargetName(event.target.value)}
            placeholder="Reporting service"
            autoComplete="off"
          />
          <label htmlFor={databaseFieldId}>Database name</label>
          <input
            id={databaseFieldId}
            value={databaseName}
            onChange={(event) => setDatabaseName(event.target.value)}
            placeholder="reporting_db"
            autoComplete="off"
          />
          <p className="property-hint">
            Each destination produces its own backend with its own database. No credentials are stored here.
          </p>
          <button className="button button-secondary" type="submit" disabled={busy}>Create destination</button>
        </form>
      )}

      <div className="generation-section">
        <h3>Destination</h3>
        {targets.length === 0 ? (
          <p className="property-hint">
            This model has no destination yet. Create one to choose where the generated backend belongs.
          </p>
        ) : (
          <ul className="generation-target-list">
            {targets.map((target) => (
              <li key={target.targetId}>
                <button
                  type="button"
                  className="generation-target-option"
                  aria-pressed={target.targetId === selectedTargetId}
                  onClick={() => onSelectTarget(target.targetId)}
                >
                  {targetOptionLabel(target)}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="generation-section">
        <h3>Profile</h3>
        {profiles.length === 0 ? (
          <p className="property-hint">The generation profile is not available yet.</p>
        ) : (
          <ul className="generation-target-list">
            {profiles.map((profile, index) => (
              <li key={index}>
                <button
                  type="button"
                  className="generation-target-option"
                  aria-pressed={profile.profileId === selectedProfileId}
                  onClick={() => onSelectProfile(profile.profileId)}
                >
                  {profile.name}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="generation-section generation-actions">
        <button
          className="button button-primary"
          type="button"
          onClick={onGenerate}
          disabled={busy || !selectedTarget || !selectedProfile}
        >
          {busy ? 'Generating…' : 'Generate backend'}
        </button>
        {ready && (
          <button className="button button-secondary" type="button" onClick={onDownload} disabled={busy}>
            Download ZIP
          </button>
        )}
      </div>

      {run && (
        <div className="generation-run" aria-live="polite">
          <p className="generation-run-state" data-state={run.state satisfies GenerationState}>
            {describeRunState(run.state)}
          </p>
          {diagnostics.length > 0 && (
            <ul className="generation-diagnostics">
              {diagnostics.map((diagnostic, index) => (
                <li key={`${diagnostic.feature}-${index}`}>
                  <strong>{diagnostic.feature}</strong>
                  <span>{diagnostic.detail}</span>
                </li>
              ))}
            </ul>
          )}
          {!isTerminalState(run.state) && (
            <p className="property-hint">The run is still working. This panel updates on its own.</p>
          )}
        </div>
      )}

      {(run !== null || deployment !== null) && (
        <div className="generation-deployment" aria-live="polite">
          <h3>Deployment</h3>
          {deployment !== null && (
            <>
              <p className="generation-run-state" data-state={deployment.state}>
                {describeDeploymentState(deployment.state)}
              </p>
              {deployment.state === 'UP' && phoneUrl && (
                <div className="generation-address">
                  <p className="property-hint">From another device on the same network:</p>
                  <code>{phoneUrl}</code>
                  {editorOnLocalhost && (
                    <p className="property-hint">
                      This editor was opened through localhost, so that address only works here. Open the
                      editor from this machine's address on the network and the same address will work from a
                      phone.
                    </p>
                  )}
                </div>
              )}
              {deployment.state === 'FAILED' && (
                <ul className="generation-diagnostics">
                  <li>
                    <strong>{describeDeploymentFailure(deployment.failureCode)}</strong>
                    {safeDeploymentDetail(deployment.failureMessage) !== null && (
                      <span>{safeDeploymentDetail(deployment.failureMessage)}</span>
                    )}
                  </li>
                </ul>
              )}
              {isDeploymentActive(deployment.state) && deployment.state !== 'UP' && (
                <p className="property-hint">The operator on this machine picks this up on its own.</p>
              )}
              {canStopDeployment(deployment.state) && (
                <button className="button button-secondary" type="button" onClick={onStopDeployment}
                  disabled={busy}>
                  Stop backend
                </button>
              )}
            </>
          )}
          {run !== null && (deployment === null || !isDeploymentActive(deployment.state)) && (
            <>
              <p className="property-hint">
                Deploying runs this backend on this machine with its own database, so another device on the
                same network can call it.
              </p>
              <button
                className="button button-primary"
                type="button"
                onClick={onDeploy}
                disabled={busy || !ready}
              >
                Deploy backend
              </button>
            </>
          )}
          <p className="generation-warning">{NO_AUTHENTICATION_WARNING}</p>
        </div>
      )}
    </section>
  );
}

export interface GenerationPanelProps {
  projectId: Uuid;
  modelId: Uuid;
  versionId: Uuid;
  /** Reloads the destinations and the profile whenever the model changes. */
  modelRevision: number;
  editingLease: EditingLeaseAdapter;
}

/**
 * The container: it owns the requests, the selected destination and the poll of a run that is not
 * finished. Generation is driven entirely through the published API.
 */
export default function GenerationPanel({
  projectId,
  modelId,
  versionId,
  modelRevision,
  editingLease,
}: GenerationPanelProps) {
  const [targets, setTargets] = useState<GenerationTargetResource[]>([]);
  const [profiles, setProfiles] = useState<GenerationProfileResource[]>([]);
  const [selectedTargetId, setSelectedTargetId] = useState<string | null>(null);
  const [selectedProfileId, setSelectedProfileId] = useState<string | null>(null);
  const [run, setRun] = useState<GenerationRunResource | null>(null);
  const [deployments, setDeployments] = useState<DeploymentResource[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const activeRunRef = useRef<GenerationRunResource | null>(null);

  const publishRun = useCallback((next: GenerationRunResource | null) => {
    activeRunRef.current = next;
    setRun(next);
  }, []);

  const captureLeaseConflict = useCallback((carrier: unknown): boolean => {
    const conflict = mapEditingLeaseConflict(carrier);
    if (conflict === null) {
      return false;
    }
    editingLease.recordConflict(conflict);
    return true;
  }, [editingLease.recordConflict]);

  const loadDestinations = useCallback(async () => {
    const [loadedTargets, loadedProfiles, loadedDeployments] = await Promise.all([
      api.listGenerationTargets(projectId),
      api.listGenerationProfiles(projectId),
      api.listDeployments(projectId),
    ]);
    setTargets(loadedTargets);
    setProfiles(loadedProfiles);
    setDeployments(loadedDeployments);
    setSelectedTargetId((current) => (
      current && loadedTargets.some((target) => target.targetId === current)
        ? current
        : loadedTargets[0]?.targetId ?? null
    ));
    setSelectedProfileId((current) => (
      current && loadedProfiles.some((profile) => profile.profileId === current)
        ? current
        : loadedProfiles[0]?.profileId ?? null
    ));
  }, [projectId]);

  useEffect(() => {
    let active = true;
    publishRun(null);
    setError(null);
    setNotice(null);
    void loadDestinations().catch((cause: unknown) => {
      if (active) {
        setError(messageFor(cause));
      }
    });
    return () => {
      active = false;
    };
  }, [loadDestinations, modelRevision, publishRun]);

  useEffect(() => {
    if (!run || isTerminalState(run.state)) {
      return undefined;
    }
    let active = true;
    const timer = window.setTimeout(() => {
      void api.getGeneration(projectId, run.runId)
        .then((outcome) => {
          if (active && outcome.result) {
            publishRun(outcome.result);
          }
        })
        .catch((cause: unknown) => {
          if (active) {
            setError(messageFor(cause));
          }
        });
    }, POLL_INTERVAL_MS);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [projectId, publishRun, run]);

  const createTarget = useCallback(async (name: string, databaseName: string) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const outcome = await api.createGenerationTarget(projectId, {
        targetKey: uniqueTargetKey(suggestedTargetKey(name), targets.map((target) => target.targetKey)),
        name,
        databaseEngine: 'POSTGRESQL',
        databaseName,
        databaseSchema: null,
        connectionConfigRef: null,
      });
      if (outcome.result) {
        await loadDestinations();
        setSelectedTargetId(outcome.result.targetId);
        setNotice(`${outcome.result.name} is ready as a destination.`);
      } else {
        setError(outcome.message);
      }
    } catch (cause: unknown) {
      setError(messageFor(cause));
    } finally {
      setBusy(false);
    }
  }, [loadDestinations, projectId, targets]);

  const generate = useCallback(async () => {
    const profile = profiles.find((candidate) => candidate.profileId === selectedProfileId);
    if (!selectedTargetId || !profile) {
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const outcome = await api.startGeneration(projectId, {
        modelId,
        versionId,
        targetId: selectedTargetId,
        profileId: profile.profileId,
        templateId: profile.templateId,
        idempotencyKey: crypto.randomUUID(),
      });
      if (captureLeaseConflict(outcome)) {
        return;
      }
      publishRun(outcome.result);
      if (!outcome.result) {
        setError(outcome.message);
      } else if (outcome.result.state === 'FAILED') {
        setError('The model cannot be generated yet. See what blocks it below.');
      }
    } catch (cause: unknown) {
      if (!captureLeaseConflict(cause)) {
        setError(messageFor(cause));
      }
    } finally {
      setBusy(false);
    }
  }, [captureLeaseConflict, modelId, profiles, projectId, publishRun, selectedProfileId, selectedTargetId, versionId]);

  const loadDeployments = useCallback(async () => {
    setDeployments(await api.listDeployments(projectId));
  }, [projectId]);

  // The deployment of the run on screen, or the one the selected destination is holding. A reload loses the
  // run, which only ever lived in memory, but the backend it started keeps running: the user has to be able to
  // see it and stop it without generating again.
  const runDeployments = run === null ? [] : deployments.filter((item) => item.runId === run.runId);
  const targetDeployments = deployments.filter((item) => item.targetId === selectedTargetId);
  const deployment = runDeployments.find((item) => isDeploymentActive(item.state))
    ?? runDeployments[0]
    ?? targetDeployments.find((item) => isDeploymentActive(item.state))
    ?? targetDeployments[0]
    ?? null;
  const browserHost = typeof window === 'undefined' ? 'localhost' : window.location.hostname;
  const phoneUrl = deployment === null ? null : phoneBaseUrl(browserHost, deployment.httpHostPort);
  const editorOnLocalhost = editorIsLocal(browserHost);

  useEffect(() => {
    if (deployment === null || isDeploymentTerminal(deployment.state)) {
      return undefined;
    }
    // The operator works on its own schedule, so the panel asks again while there is something to wait for.
    const timer = window.setTimeout(() => {
      void loadDeployments().catch(() => undefined);
    }, POLL_INTERVAL_MS);
    return () => window.clearTimeout(timer);
  }, [deployment, loadDeployments]);

  const deploy = useCallback(async () => {
    if (run === null) {
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const ordered = await api.requestDeployment(projectId, run.runId);
      setDeployments((current) => [ordered, ...current.filter(
        (item) => item.deploymentId !== ordered.deploymentId)]);
      setNotice('The deployment was ordered. The operator on this machine picks it up on its own.');
    } catch (cause: unknown) {
      setError(messageFor(cause));
    } finally {
      setBusy(false);
    }
  }, [projectId, run]);

  const stopDeployment = useCallback(async () => {
    if (deployment === null) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const stopped = await api.stopDeployment(deployment.deploymentId);
      setDeployments((current) => current.map(
        (item) => (item.deploymentId === stopped.deploymentId ? stopped : item)));
    } catch (cause: unknown) {
      setError(messageFor(cause));
    } finally {
      setBusy(false);
    }
  }, [deployment]);

  const download = useCallback(async () => {
    const current = activeRunRef.current;
    if (!current) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result = await api.downloadGenerationArchive(projectId, current.runId);
      if (result.kind === 'rejected') {
        setError(result.message);
        return;
      }
      const url = URL.createObjectURL(result.blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = result.filename;
      document.body.append(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
      setNotice('The generated backend was downloaded as a ZIP archive.');
    } catch (cause: unknown) {
      if (!captureLeaseConflict(cause)) {
        setError(messageFor(cause));
      }
    } finally {
      setBusy(false);
    }
  }, [captureLeaseConflict, projectId]);

  return (
    <GenerationPanelView
      targets={targets}
      profiles={profiles}
      selectedTargetId={selectedTargetId}
      selectedProfileId={selectedProfileId}
      run={run}
      deployment={deployment}
      phoneUrl={phoneUrl}
      editorOnLocalhost={editorOnLocalhost}
      busy={busy}
      error={error}
      notice={notice}
      editingLeaseConflict={editingLease.conflict}
      reclaimBusy={editingLease.reclaimBusy}
      onReclaimLease={() => {
        void editingLease.reclaim();
      }}
      onSelectTarget={setSelectedTargetId}
      onSelectProfile={setSelectedProfileId}
      onCreateTarget={(name, databaseName) => {
        void createTarget(name, databaseName);
      }}
      onGenerate={() => {
        void generate();
      }}
      onDownload={() => {
        void download();
      }}
      onDeploy={() => {
        void deploy();
      }}
      onStopDeployment={() => {
        void stopDeployment();
      }}
      onDismiss={() => setError(null)}
    />
  );
}
