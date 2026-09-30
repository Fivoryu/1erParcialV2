import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { ApiTransportError, createApiClient } from '../api/client';
import type {
  GenerationProfileResource,
  GenerationRunResource,
  GenerationState,
  GenerationTargetResource,
  Uuid,
} from '../api/types';
import {
  blockingDiagnostics,
  canDownloadArchive,
  describeRunState,
  isTerminalState,
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
  busy: boolean;
  error: string | null;
  notice: string | null;
  onSelectTarget(targetId: string): void;
  onSelectProfile(profileId: string): void;
  onCreateTarget(name: string, databaseName: string): void;
  onGenerate(): void;
  onDownload(): void;
  onDismiss(): void;
}

export function GenerationPanelView({
  targets,
  profiles,
  selectedTargetId,
  selectedProfileId,
  run,
  busy,
  error,
  notice,
  onSelectTarget,
  onSelectProfile,
  onCreateTarget,
  onGenerate,
  onDownload,
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
    </section>
  );
}

export interface GenerationPanelProps {
  projectId: Uuid;
  modelId: Uuid;
  versionId: Uuid;
  /** Reloads the destinations and the profile whenever the model changes. */
  modelRevision: number;
}

/**
 * The container: it owns the requests, the selected destination and the poll of a run that is not
 * finished. Generation is driven entirely through the published API.
 */
export default function GenerationPanel({ projectId, modelId, versionId, modelRevision }: GenerationPanelProps) {
  const [targets, setTargets] = useState<GenerationTargetResource[]>([]);
  const [profiles, setProfiles] = useState<GenerationProfileResource[]>([]);
  const [selectedTargetId, setSelectedTargetId] = useState<string | null>(null);
  const [selectedProfileId, setSelectedProfileId] = useState<string | null>(null);
  const [run, setRun] = useState<GenerationRunResource | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const activeRunRef = useRef<GenerationRunResource | null>(null);

  const publishRun = useCallback((next: GenerationRunResource | null) => {
    activeRunRef.current = next;
    setRun(next);
  }, []);

  const loadDestinations = useCallback(async () => {
    const [loadedTargets, loadedProfiles] = await Promise.all([
      api.listGenerationTargets(projectId),
      api.listGenerationProfiles(projectId),
    ]);
    setTargets(loadedTargets);
    setProfiles(loadedProfiles);
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
      publishRun(outcome.result);
      if (!outcome.result) {
        setError(outcome.message);
      } else if (outcome.result.state === 'FAILED') {
        setError('The model cannot be generated yet. See what blocks it below.');
      }
    } catch (cause: unknown) {
      setError(messageFor(cause));
    } finally {
      setBusy(false);
    }
  }, [modelId, profiles, projectId, publishRun, selectedProfileId, selectedTargetId, versionId]);

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
      setError(messageFor(cause));
    } finally {
      setBusy(false);
    }
  }, [projectId]);

  return (
    <GenerationPanelView
      targets={targets}
      profiles={profiles}
      selectedTargetId={selectedTargetId}
      selectedProfileId={selectedProfileId}
      run={run}
      busy={busy}
      error={error}
      notice={notice}
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
      onDismiss={() => setError(null)}
    />
  );
}
