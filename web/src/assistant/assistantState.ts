import type {
  AssistantApplyResource,
  AssistantApplyStepResource,
  AssistantPlanResource,
} from '../api/assistant';
import { ApiTransportError } from '../api/client';
import { mapEditingLeaseConflict } from '../collaboration/editingLease';

/**
 * Observable phases of the assistant panel (frozen IA4-05 design §9):
 * idle | analyzing | clarify | preview | applying | done | cancelled | recoverable error.
 */
export type AssistantState =
  | { phase: 'idle' }
  | { phase: 'analyzing'; utterance: string }
  | { phase: 'clarify'; utterance: string; question: string }
  | { phase: 'preview'; utterance: string; plan: AssistantPlanResource; baseRevision: number; stale: boolean }
  | { phase: 'applying'; utterance: string; plan: AssistantPlanResource; baseRevision: number }
  | { phase: 'done'; kind: 'applied'; plan: AssistantPlanResource; steps: AssistantApplyStepResource[]; finalRevision: number }
  | { phase: 'done'; kind: 'explain'; explanation: string }
  | { phase: 'cancelled' }
  | { phase: 'error'; message: string; retry: AssistantRetryAction };

export type AssistantRetryAction =
  | { kind: 'ask'; utterance: string }
  | { kind: 'confirm' };

export type AssistantEvent =
  | { type: 'ask_started'; utterance: string }
  | { type: 'plan_succeeded'; plan: AssistantPlanResource; baseRevision: number }
  | { type: 'plan_failed'; message: string; utterance: string }
  | { type: 'apply_started' }
  | { type: 'apply_succeeded'; result: AssistantApplyResource }
  | { type: 'apply_failed'; message: string }
  | { type: 'revision_observed'; revision: number }
  | { type: 'cancel_requested' }
  | { type: 'dismissed' };

export { describeAssistantOperation } from './assistantNames';

export const STEP_STATUS_LABELS: Record<AssistantApplyStepResource['status'], string> = {
  ACCEPTED: 'Aceptado',
  REJECTED: 'Rechazado',
  SKIPPED: 'Omitido',
};

/** Confirm is only enabled for a fresh PLAN preview with operations (frozen §9). */
export function canConfirm(state: AssistantState): boolean {
  return state.phase === 'preview'
    && !state.stale
    && state.plan.intent === 'PLAN'
    && state.plan.operations.length > 0;
}

/** Maps a plan response onto the observable states, keeping REJECT recoverable by re-asking. */
function stateFromPlan(plan: AssistantPlanResource, baseRevision: number, utterance: string): AssistantState {
  if (plan.intent === 'PLAN' && plan.operations.length > 0) {
    return { phase: 'preview', utterance, plan, baseRevision, stale: false };
  }
  if (plan.intent === 'CLARIFY' && plan.question) {
    return { phase: 'clarify', utterance, question: plan.question };
  }
  if (plan.intent === 'EXPLAIN' && plan.explanation) {
    return { phase: 'done', kind: 'explain', explanation: plan.explanation };
  }
  const message = plan.diagnostics[0]?.message
    ?? (plan.intent === 'REJECT'
      ? 'El asistente no pudo interpretar la consigna dentro del catálogo.'
      : 'La respuesta del asistente no contiene operaciones aplicables.');
  return { phase: 'error', message, retry: { kind: 'ask', utterance } };
}

/**
 * Pure state machine of the assistant panel. Extracted so the frozen IA4-05
 * behavior is testable without React (no component-test runner in this project).
 */
export function transitionAssistantState(state: AssistantState, event: AssistantEvent): AssistantState {
  switch (event.type) {
    case 'ask_started':
      return { phase: 'analyzing', utterance: event.utterance };
    case 'plan_succeeded':
      return state.phase === 'analyzing'
        ? stateFromPlan(event.plan, event.baseRevision, state.utterance)
        : state;
    case 'plan_failed':
      return state.phase === 'analyzing'
        ? { phase: 'error', message: event.message, retry: { kind: 'ask', utterance: state.utterance } }
        : state;
    case 'apply_started':
      return state.phase === 'preview'
        ? { phase: 'applying', utterance: state.utterance, plan: state.plan, baseRevision: state.baseRevision }
        : state;
    case 'apply_succeeded':
      return state.phase === 'applying'
        ? {
          phase: 'done',
          kind: 'applied',
          plan: state.plan,
          steps: event.result.steps,
          finalRevision: event.result.finalRevision,
        }
        : state;
    case 'apply_failed':
      return state.phase === 'applying'
        ? { phase: 'error', message: event.message, retry: { kind: 'confirm' } }
        : state;
    case 'revision_observed':
      // Frozen §9: a model revision change between plan and confirm marks the preview STALE.
      return state.phase === 'preview' && !state.stale && event.revision !== state.baseRevision
        ? { ...state, stale: true }
        : state;
    case 'cancel_requested':
      return state.phase === 'analyzing'
        || state.phase === 'clarify'
        || state.phase === 'preview'
        || state.phase === 'applying'
        ? { phase: 'cancelled' }
        : state;
    case 'dismissed':
      return state.phase === 'done' || state.phase === 'cancelled' || state.phase === 'error'
        ? { phase: 'idle' }
        : state;
  }
}

/** messageFor-style mapping reused by the panel for recoverable errors (frozen §9). */
export function assistantErrorMessageFor(error: unknown, fallback: string): string {
  if (!(error instanceof ApiTransportError)) {
    return fallback;
  }
  if (error.code === 'EXPORT_BLOCKED_EDITING') {
    const conflict = mapEditingLeaseConflict({ code: error.code, details: error.details });
    if (conflict) {
      return `El modelo está siendo editado por ${conflict.editorDisplayName}. `
        + `Esperá ${conflict.retryAfterSeconds}s o reclamá la concesión para volver a aplicar.`;
    }
  }
  return error.message;
}
