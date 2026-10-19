import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { createApiClient, type ApiClient } from '../api/client';
import type {
  GenerationReadinessResource,
  MemberRole,
  ModelResource,
  ModelValidationResource,
} from '../api/types';
import { buildReviewFindings, buildReviewPrompt, type ReviewFinding } from './reviewFindings';

const defaultApi = createApiClient();
const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;

export type ReviewPanelState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'loaded'; validation: ModelValidationResource; readiness: GenerationReadinessResource };

export interface ReviewPanelProps {
  model: ModelResource;
  role: MemberRole | null;
  state: ReviewPanelState;
  onRunReview(): void;
  onExplain(prompt: string): void;
  onProposeFix(prompt: string): void;
}

function ReviewHeader({ busy, onRunReview }: { busy: boolean; onRunReview(): void }): ReactElement {
  return (
    <header className="review-heading">
      <div>
        <p className="eyebrow">Diagram checks</p>
        <h2 id="review-title">Review</h2>
      </div>
      <button className="button button-secondary button-compact review-run-button" type="button" disabled={busy} onClick={onRunReview}>
        Run review
      </button>
    </header>
  );
}

function FindingList({ findings, label, kind }: {
  findings: ReviewFinding[];
  label: string;
  kind: 'error' | 'warning' | 'blocker';
}): ReactElement {
  return (
    <section className={`review-findings review-findings--${kind}`} aria-label={label}>
      <h4>{label}</h4>
      <ul>
        {findings.map((finding, index) => (
          <li key={`${finding.code}-${finding.elementName}-${index}`}>
            <strong className="review-finding-code">{finding.code}</strong>
            <span className="review-finding-element">{finding.elementName}</span>
            <span className="review-finding-message">{finding.message}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Pure rendering surface, split from the API loader for deterministic markup tests. */
export function ReviewPanel({ model, role, state, onRunReview, onExplain, onProposeFix }: ReviewPanelProps): ReactElement {
  if (state.status === 'loading') {
    return (
      <section className="review-panel" aria-labelledby="review-title">
        <ReviewHeader busy onRunReview={onRunReview} />
        <p className="review-loading" role="status">Loading UML validation and backend readiness…</p>
      </section>
    );
  }

  if (state.status === 'error') {
    return (
      <section className="review-panel" aria-labelledby="review-title">
        <ReviewHeader busy={false} onRunReview={onRunReview} />
        <p className="review-error" role="alert">{state.message}</p>
      </section>
    );
  }

  const findings = buildReviewFindings(state.validation, state.readiness, model);
  const hasFindings = findings.errors.length > 0
    || findings.warnings.length > 0
    || findings.readinessBlockers.length > 0;
  const canUseAssistant = role === 'OWNER' || role === 'EDITOR';
  const actionsEnabled = hasFindings && canUseAssistant;
  const validationIsClean = findings.errors.length === 0 && findings.warnings.length === 0;
  const reviewIsClean = validationIsClean && state.readiness.diagnostics.length === 0 && findings.ready;

  return (
    <section className="review-panel" aria-labelledby="review-title">
      <ReviewHeader busy={false} onRunReview={onRunReview} />
      <div className="review-summary" aria-label="Review summary">
        <span className="review-count review-count--error">Errors: {findings.errors.length}</span>
        <span className="review-count review-count--warning">Warnings: {findings.warnings.length}</span>
        <span className={`review-readiness-badge${findings.ready ? ' is-ready' : ' is-not-ready'}`}>
          {findings.ready ? 'Ready' : 'Not ready'}
        </span>
      </div>

      <section className="review-validation" aria-label="UML validation findings">
        <h3>UML validation</h3>
        {reviewIsClean ? <p className="review-clean">No issues found</p> : null}
        {!reviewIsClean && validationIsClean ? (
          <p className="review-clean">No UML validation issues found.</p>
        ) : null}
        {findings.errors.length > 0 ? <FindingList findings={findings.errors} kind="error" label={`Errors (${findings.errors.length})`} /> : null}
        {findings.warnings.length > 0 ? <FindingList findings={findings.warnings} kind="warning" label={`Warnings (${findings.warnings.length})`} /> : null}
      </section>

      <section className="review-readiness" aria-label="Backend readiness details">
        <h3>Backend readiness</h3>
        <p className="review-profile"><span>Profile:</span> {findings.profile}</p>
        <p className={`review-readiness-status${findings.ready ? ' is-ready' : ' is-not-ready'}`}>
          {findings.ready ? 'Ready' : 'Not ready'}
        </p>
        {findings.readinessBlockers.length > 0 ? (
          <FindingList
            findings={findings.readinessBlockers}
            kind="blocker"
            label={`Blocking diagnostics (${findings.readinessBlockers.length})`}
          />
        ) : findings.ready ? (
          <p className="review-clean">No blocking issues found for this profile.</p>
        ) : (
          <p className="review-empty-blockers">The backend is not ready, but no blocking diagnostics were provided.</p>
        )}
        <p className="review-generation-note">
          Destination-specific checks, such as the database engine, are handled in Generation.
        </p>
      </section>

      <section className="review-ai-actions" aria-label="AI review actions">
        <button
          className="button button-secondary"
          type="button"
          disabled={!actionsEnabled}
          onClick={() => onExplain(buildReviewPrompt(findings, 'explain'))}
        >
          Explain with AI
        </button>
        <button
          className="button button-primary"
          type="button"
          disabled={!actionsEnabled}
          onClick={() => onProposeFix(buildReviewPrompt(findings, 'propose-fix'))}
        >
          Propose a fix
        </button>
        {!canUseAssistant ? <p className="review-access-note">AI actions require editing access.</p> : null}
        {canUseAssistant && !hasFindings ? <p className="review-access-note">AI actions are available when review finds an issue.</p> : null}
      </section>
    </section>
  );
}

export interface ReviewPanelLoaderProps {
  projectId: string;
  model: ModelResource;
  role: MemberRole | null;
  onExplain(prompt: string): void;
  onProposeFix(prompt: string): void;
  api?: Pick<ApiClient, 'getModelValidation' | 'getGenerationReadiness'>;
}

function messageForReviewError(error: unknown): string {
  const message = error instanceof Error ? error.message.replace(UUID_PATTERN, 'the diagram element').trim() : '';
  return message || 'The review could not be loaded. Try again.';
}

/** Loads both read-only checks together when Review opens and whenever the model version changes. */
export function ReviewPanelLoader({
  projectId,
  model,
  role,
  onExplain,
  onProposeFix,
  api = defaultApi,
}: ReviewPanelLoaderProps): ReactElement {
  const [state, setState] = useState<ReviewPanelState>({ status: 'loading' });
  const requestSequence = useRef(0);

  const runReview = useCallback(async (): Promise<void> => {
    const requestId = ++requestSequence.current;
    setState({ status: 'loading' });
    try {
      const [validation, readiness] = await Promise.all([
        api.getModelValidation(projectId),
        api.getGenerationReadiness(projectId, model.versionId),
      ]);
      if (requestSequence.current === requestId) {
        setState({ status: 'loaded', validation, readiness });
      }
    } catch (error: unknown) {
      if (requestSequence.current === requestId) {
        setState({ status: 'error', message: messageForReviewError(error) });
      }
    }
  }, [api, model.versionId, projectId]);

  useEffect(() => {
    void runReview();
    return () => {
      requestSequence.current += 1;
    };
  }, [model.revision, runReview]);

  return (
    <ReviewPanel
      model={model}
      role={role}
      state={state}
      onRunReview={() => void runReview()}
      onExplain={onExplain}
      onProposeFix={onProposeFix}
    />
  );
}
