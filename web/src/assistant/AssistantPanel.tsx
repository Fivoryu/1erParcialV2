import type { FormEvent } from 'react';
import type { Uuid } from '../api/types';
import { describeAssistantOperation, STEP_STATUS_LABELS, type AssistantState } from './assistantState';
import { useAssistantPanel } from './useAssistantPanel';

export interface AssistantPanelViewProps {
  state: AssistantState;
  utterance: string;
  canConfirm: boolean;
  onUtteranceChange(value: string): void;
  onAsk(): void;
  onAnswerClarification(text: string): void;
  onConfirm(): void;
  onCancel(): void;
  onRetry(): void;
  onDismiss(): void;
}

function CancelButton({ onCancel }: { onCancel(): void }) {
  return (
    <button className="button button-secondary" onClick={onCancel} type="button">
      Cancelar
    </button>
  );
}

function PreviewList({ state }: { state: Extract<AssistantState, { phase: 'preview' }> }) {
  return (
    <ul className="generation-diagnostics">
      {state.plan.operations.map((operation, index) => (
        <li key={`${operation.type}-${index}`}>
          <strong>{describeAssistantOperation(operation)}</strong>
        </li>
      ))}
    </ul>
  );
}

function StepList({ state }: { state: Extract<AssistantState, { phase: 'done' }> & { kind: 'applied' } }) {
  return (
    <ul className="generation-diagnostics">
      {state.steps.map((step) => (
        <li key={step.index}>
          <strong>{step.type ? describeAssistantOperation({ type: step.type, targetId: step.targetId, args: {} }) : `Paso ${step.index + 1}`}</strong>
          <span>{STEP_STATUS_LABELS[step.status]}</span>
          {step.diagnostics.map((diagnostic, index) => (
            <span key={`${diagnostic.code}-${index}`}>{diagnostic.message}</span>
          ))}
        </li>
      ))}
    </ul>
  );
}

/** Presentation only: no inference per frame, all async work lives in the hook. */
export function AssistantPanelView({
  state,
  utterance,
  canConfirm,
  onUtteranceChange,
  onAsk,
  onAnswerClarification,
  onConfirm,
  onCancel,
  onRetry,
  onDismiss,
}: AssistantPanelViewProps) {
  const requestInFlight = state.phase === 'analyzing' || state.phase === 'applying';
  const cancellable = requestInFlight || state.phase === 'preview' || state.phase === 'clarify';

  function handleAskSubmit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    onAsk();
  }

  function handleClarifySubmit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    const answer = utterance.trim();
    if (answer) {
      onAnswerClarification(answer);
    }
  }

  return (
    <section className="surface chat-panel assistant-panel" aria-labelledby="assistant-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">IA textual</p>
          <h2 id="assistant-title">Asistente de modelo</h2>
        </div>
        {cancellable && <CancelButton onCancel={onCancel} />}
      </div>

      <form className="chat-compose-form" onSubmit={handleAskSubmit}>
        <label className="sr-only" htmlFor="assistant-utterance">Consigna para el asistente</label>
        <input
          autoComplete="off"
          id="assistant-utterance"
          onChange={(event) => onUtteranceChange(event.target.value)}
          placeholder="Describí el cambio que querés hacer en el modelo…"
          type="text"
          value={utterance}
        />
        <button className="button button-primary" disabled={requestInFlight || !utterance.trim()} type="submit">
          {state.phase === 'analyzing' ? 'Analizando…' : 'Consultar'}
        </button>
      </form>

      {state.phase === 'analyzing' && (
        <p className="quiet-state" role="status">Analizando la consigna…</p>
      )}

      {state.phase === 'applying' && (
        <p className="generation-notice" role="status">Aplicando los cambios del plan…</p>
      )}

      {state.phase === 'clarify' && (
        <div className="generation-section">
          <h3>Aclaración necesaria</h3>
          <p className="property-hint">{state.question}</p>
          <form className="chat-compose-form" onSubmit={handleClarifySubmit}>
            <label className="sr-only" htmlFor="assistant-clarification">Respuesta a la aclaración</label>
            <input
              autoComplete="off"
              id="assistant-clarification"
              onChange={(event) => onUtteranceChange(event.target.value)}
              placeholder="Respondé la pregunta del asistente…"
              type="text"
              value={utterance}
            />
            <button className="button button-primary" disabled={!utterance.trim()} type="submit">
              Responder
            </button>
          </form>
        </div>
      )}

      {state.phase === 'preview' && (
        <div className="generation-run" aria-live="polite">
          <p className="generation-run-state">
            Vista previa: nada se aplicó todavía. Confirmá para ejecutar las operaciones.
          </p>
          {state.stale && (
            <div className="notice notice-error" role="alert">
              El modelo cambió desde que se generó la vista previa. Volvé a consultar antes de aplicar.
            </div>
          )}
          <PreviewList state={state} />
          <div className="generation-actions">
            <button className="button button-primary" disabled={!canConfirm} onClick={onConfirm} type="button">
              Aplicar cambios
            </button>
            <CancelButton onCancel={onCancel} />
          </div>
        </div>
      )}

      {state.phase === 'done' && state.kind === 'applied' && (
        <div className="generation-run" aria-live="polite">
          <p className="generation-run-state">Cambios aplicados. Revisión final: {state.finalRevision}.</p>
          <StepList state={state} />
          <button className="text-button" onClick={onDismiss} type="button">Listo</button>
        </div>
      )}

      {state.phase === 'done' && state.kind === 'explain' && (
        <div className="generation-run" aria-live="polite">
          <p className="generation-run-state">{state.explanation}</p>
          <button className="text-button" onClick={onDismiss} type="button">Listo</button>
        </div>
      )}

      {state.phase === 'cancelled' && (
        <p className="quiet-state" role="status">
          Consulta cancelada. No se modificó el modelo.
          <button className="text-button" onClick={onDismiss} type="button">Descartar</button>
        </p>
      )}

      {state.phase === 'error' && (
        <div className="notice notice-error" role="alert">
          <span>{state.message}</span>
          <button className="text-button" onClick={onRetry} type="button">Reintentar</button>
          <button className="text-button" onClick={onDismiss} type="button">Descartar</button>
        </div>
      )}
    </section>
  );
}

export interface AssistantPanelProps {
  projectId: Uuid;
  /** Live model revision used for stale-preview detection (frozen IA4-05 §9). */
  currentRevision: number;
}

export default function AssistantPanel({ projectId, currentRevision }: AssistantPanelProps) {
  const assistant = useAssistantPanel({ projectId, currentRevision });

  return (
    <AssistantPanelView
      canConfirm={assistant.canConfirm}
      onAsk={assistant.ask}
      onAnswerClarification={assistant.answerClarification}
      onCancel={assistant.cancel}
      onConfirm={assistant.confirm}
      onDismiss={assistant.dismiss}
      onRetry={assistant.retry}
      onUtteranceChange={assistant.setUtterance}
      state={assistant.state}
      utterance={assistant.utterance}
    />
  );
}
