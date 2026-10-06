import { useEffect } from 'react';
import type { FormEvent } from 'react';
import type { Uuid } from '../api/types';
import type { SpeechRecognitionErrorReason } from './speechRecognition';
import { describeAssistantOperation, STEP_STATUS_LABELS, type AssistantState } from './assistantState';
import { useAssistantPanel } from './useAssistantPanel';
import { useSpeechRecognition } from './useSpeechRecognition';

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
  /** Voice dictation state; omit (or supported: false) to hide the mic button. */
  voice?: AssistantVoiceProps | undefined;
}

/**
 * Appends a dictation final transcript to the text the user already typed:
 * space-separated, trimmed on both sides, and a no-op on an empty transcript
 * so nothing the user wrote is ever discarded.
 */
export function appendTranscript(existing: string, final: string): string {
  const appended = final.trim();
  if (appended.length === 0) {
    return existing;
  }
  const current = existing.trim();
  return current.length === 0 ? appended : `${current} ${appended}`;
}

/**
 * Spanish UI copy per frozen reason code (voz-asistente-ia.md §3 V2). The
 * silent reasons (no-speech/aborted) never persist in the machine, but the
 * mapping stays exhaustive so every reason has a human-readable message.
 */
const VOICE_ERROR_MESSAGES: Record<SpeechRecognitionErrorReason, string> = {
  'not-supported': 'El dictado por voz no está disponible en este navegador.',
  'not-allowed': 'Permiso de micrófono denegado.',
  'no-speech': 'No se detectó voz; intentá de nuevo.',
  aborted: 'El dictado se detuvo.',
  network: 'Error de red durante el dictado.',
  unknown: 'No se pudo completar el dictado.',
};

export function voiceErrorMessage(reason: SpeechRecognitionErrorReason): string {
  return VOICE_ERROR_MESSAGES[reason];
}

/** Voice dictation props consumed by the view; undefined hides the mic. */
export interface AssistantVoiceProps {
  supported: boolean;
  listening: boolean;
  interim: string;
  error: SpeechRecognitionErrorReason | null;
  onToggle(): void;
}

function MicButton({ listening, error, onToggle }: {
  listening: boolean;
  error: SpeechRecognitionErrorReason | null;
  onToggle(): void;
}) {
  const label = listening ? 'Detener dictado' : 'Dictar por voz';
  return (
    <button
      aria-label={label}
      aria-pressed={listening}
      className={listening ? 'voice-mic-button voice-mic-listening' : 'voice-mic-button'}
      onClick={onToggle}
      title={error ? voiceErrorMessage(error) : label}
      type="button"
    >
      <svg aria-hidden="true" fill="none" height="15" stroke="currentColor" strokeLinecap="round" strokeWidth="1.8" viewBox="0 0 24 24" width="15">
        <path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z" />
        <path d="M19 11v1a7 7 0 0 1-14 0v-1" />
        <path d="M12 19v3" />
      </svg>
      {listening && <span aria-hidden="true" className="voice-mic-dot" />}
    </button>
  );
}

/**
 * Least-intrusive hint under the input: interim dictation while listening,
 * the human-readable error otherwise; doubles as the live announcement.
 */
function VoiceHint({ listening, interim, error }: {
  listening: boolean;
  interim: string;
  error: SpeechRecognitionErrorReason | null;
}) {
  if (!listening && error === null) {
    return null;
  }
  const message = error
    ? voiceErrorMessage(error)
    : (interim.length > 0 ? interim : 'Escuchando…');
  return (
    <p aria-live="polite" className={error ? 'voice-hint voice-hint-error' : 'voice-hint'} role="status">
      {message}
    </p>
  );
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
  voice,
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
        {voice?.supported && (
          <MicButton error={voice.error} listening={voice.listening} onToggle={voice.onToggle} />
        )}
      </form>

      {voice?.supported && (
        <VoiceHint error={voice.error} interim={voice.interim} listening={voice.listening} />
      )}

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
            {voice?.supported && (
              <MicButton error={voice.error} listening={voice.listening} onToggle={voice.onToggle} />
            )}
          </form>
          {voice?.supported && (
            <VoiceHint error={voice.error} interim={voice.interim} listening={voice.listening} />
          )}
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
  const speech = useSpeechRecognition();

  // When the dictation ends (stop clicked or the browser ended on its own),
  // the accumulated final transcript is appended to what the user already
  // typed and cleared. reset() is a no-op while listening, so this only runs
  // after a real stop; no auto-send, the user sends it through the form.
  useEffect(() => {
    if (!speech.listening && speech.transcript.length > 0) {
      assistant.setUtterance(appendTranscript(assistant.utterance, speech.transcript));
      speech.reset();
    }
  }, [assistant, speech]);

  const voice: AssistantVoiceProps = {
    supported: speech.supported,
    listening: speech.listening,
    interim: speech.interim,
    error: speech.error,
    onToggle: () => {
      if (speech.listening) {
        speech.stop();
      } else {
        speech.start();
      }
    },
  };

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
      voice={voice}
    />
  );
}
