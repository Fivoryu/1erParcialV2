import type { AssistantApplyOperation, AssistantApi } from '../api/assistant';
import type { Uuid } from '../api/types';
import {
  assistantErrorMessageFor,
  canConfirm,
  transitionAssistantState,
  type AssistantState,
} from './assistantState';

export const ASSISTANT_FALLBACK_ERROR = 'La consulta al asistente falló. Revisá la conexión e intentá de nuevo.';

export interface AssistantControllerDeps {
  api: Pick<AssistantApi, 'requestPlan' | 'applyPlan'>;
  projectId(): Uuid | undefined;
  currentRevision(): number | null;
  read(): AssistantState;
  write(next: (current: AssistantState) => AssistantState): void;
  isAlive(): boolean;
}

export interface AssistantController {
  ask(utterance: string): void;
  answerClarification(text: string): void;
  confirm(): void;
  cancel(): void;
  retry(): void;
  dismiss(): void;
  noteRevision(revision: number): void;
  reset(): void;
}

/**
 * React-free orchestration of the assistant panel. Every asynchronous
 * continuation checks `isAlive()` (the mounted guard) and its ticket so a
 * cancelled or superseded request can never write state (frozen IA4-05 §9,
 * C21 lesson). The in-flight request is reconciled, never re-run blindly.
 */
export function createAssistantController(deps: AssistantControllerDeps): AssistantController {
  let ticket = 0;

  function isCurrent(sequence: number): boolean {
    return deps.isAlive() && sequence === ticket;
  }

  function planFailureMessage(error: unknown): string {
    return assistantErrorMessageFor(error, ASSISTANT_FALLBACK_ERROR);
  }

  function ask(utterance: string): void {
    const trimmed = utterance.trim();
    const projectId = deps.projectId();
    const baseRevision = deps.currentRevision();
    if (!trimmed || !projectId || baseRevision === null) {
      return;
    }
    const sequence = ++ticket;
    deps.write((current) => transitionAssistantState(current, { type: 'ask_started', utterance: trimmed }));
    void deps.api.requestPlan(projectId, trimmed, baseRevision)
      .then((plan) => {
        if (!isCurrent(sequence)) {
          return;
        }
        deps.write((current) => transitionAssistantState(current, { type: 'plan_succeeded', plan, baseRevision }));
        // A revision change while the plan was in flight makes the fresh preview stale immediately.
        const observed = deps.currentRevision();
        if (observed !== null && observed !== baseRevision) {
          deps.write((current) => transitionAssistantState(current, { type: 'revision_observed', revision: observed }));
        }
      })
      .catch((cause: unknown) => {
        if (!isCurrent(sequence)) {
          return;
        }
        deps.write((current) => transitionAssistantState(current, {
          type: 'plan_failed',
          message: planFailureMessage(cause),
          utterance: trimmed,
        }));
      });
  }

  function confirm(): void {
    const state = deps.read();
    const projectId = deps.projectId();
    if (!projectId || state.phase !== 'preview' || !canConfirm(state)) {
      return;
    }
    const operations: AssistantApplyOperation[] = state.plan.operations.map((operation) => ({
      type: operation.type,
      targetId: operation.targetId,
      args: operation.args,
    }));
    const baseRevision = state.baseRevision;
    const sequence = ++ticket;
    deps.write((current) => transitionAssistantState(current, { type: 'apply_started' }));
    void deps.api.applyPlan(projectId, operations, baseRevision)
      .then((result) => {
        if (!isCurrent(sequence)) {
          return;
        }
        deps.write((current) => transitionAssistantState(current, { type: 'apply_succeeded', result }));
      })
      .catch((cause: unknown) => {
        if (!isCurrent(sequence)) {
          return;
        }
        deps.write((current) => transitionAssistantState(current, {
          type: 'apply_failed',
          message: planFailureMessage(cause),
        }));
      });
  }

  return {
    ask,
    answerClarification(text) {
      if (deps.read().phase === 'clarify') {
        ask(text);
      }
    },
    confirm,
    cancel() {
      // Invalidates any in-flight ticket: the late response is dropped, not replayed.
      ticket += 1;
      deps.write((current) => transitionAssistantState(current, { type: 'cancel_requested' }));
    },
    retry() {
      const state = deps.read();
      if (state.phase !== 'error') {
        return;
      }
      if (state.retry.kind === 'ask') {
        ask(state.retry.utterance);
      } else {
        confirm();
      }
    },
    dismiss() {
      deps.write((current) => transitionAssistantState(current, { type: 'dismissed' }));
    },
    noteRevision(revision) {
      deps.write((current) => transitionAssistantState(current, { type: 'revision_observed', revision }));
    },
    reset() {
      ticket += 1;
      deps.write(() => ({ phase: 'idle' }));
    },
  };
}
