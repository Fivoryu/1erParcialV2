/**
 * Pure state machine for the assistant voice dictation (voz-asistente-ia.md §3 V1).
 * Framework-free and Web-Speech-free: the browser API is reduced here to plain
 * events, so every transition is testable without React and without jsdom
 * (repo test convention: pure logic + markup, no DOM-event tests).
 */

/** Observable phases of the dictation state machine. */
export type SpeechRecognitionPhase = 'idle' | 'listening' | 'error';

/**
 * Frozen reason codes (voz-asistente-ia.md §3). `no-speech` and `aborted` are
 * silent: they return to idle without a persistent error the UI would show.
 */
export type SpeechRecognitionErrorReason =
  | 'not-supported'
  | 'not-allowed'
  | 'no-speech'
  | 'aborted'
  | 'network'
  | 'unknown';

/** User-facing text for a dictation failure; null when the failure needs no notice. */
export function speechRecognitionErrorMessage(reason: SpeechRecognitionErrorReason | null): string | null {
  switch (reason) {
    case 'not-allowed':
      return 'Permite el acceso al micrófono para dictar.';
    case 'not-supported':
      return 'Este navegador no admite el dictado en español.';
    case 'network':
      return 'El dictado necesita conexión a internet. Revisa la red e inténtalo de nuevo.';
    case 'unknown':
      return 'No se pudo usar el dictado. Inténtalo de nuevo.';
    default:
      return null;
  }
}

export interface SpeechRecognitionState {
  phase: SpeechRecognitionPhase;
  /** Final segments concatenated with a single space separator, trimmed. */
  transcript: string;
  /** Interim (in-flight) hypothesis, held separately and never mixed into transcript. */
  interim: string;
  error: SpeechRecognitionErrorReason | null;
}

export type SpeechRecognitionMachineEvent =
  | { type: 'start' }
  | { type: 'stop' }
  | { type: 'result'; transcript: string; isFinal: boolean }
  | { type: 'error'; reason: SpeechRecognitionErrorReason }
  | { type: 'reset' };

export const INITIAL_SPEECH_RECOGNITION_STATE: SpeechRecognitionState = {
  phase: 'idle',
  transcript: '',
  interim: '',
  error: null,
};

/** Frozen dictation locale for the assistant (voz-asistente-ia.md §3). */
export const SPEECH_RECOGNITION_LANG = 'es-AR';

const SILENT_ERROR_REASONS: ReadonlySet<SpeechRecognitionErrorReason> = new Set(['no-speech', 'aborted']);

function appendFinalSegment(transcript: string, segment: string): string {
  const trimmed = segment.trim();
  if (trimmed.length === 0) {
    return transcript;
  }
  return transcript.length === 0 ? trimmed : `${transcript} ${trimmed}`;
}

/**
 * Pure state machine (same style as transitionAssistantState):
 * start (idle|error -> listening), stop (listening -> idle, transcript kept),
 * result (final appends, interim held apart), error (persistent reasons ->
 * error, no-speech/aborted silently back to idle), reset (clears everything).
 */
export function transitionSpeechRecognitionState(
  state: SpeechRecognitionState,
  event: SpeechRecognitionMachineEvent,
): SpeechRecognitionState {
  switch (event.type) {
    case 'start':
      return state.phase === 'listening'
        ? state
        : { phase: 'listening', transcript: state.transcript, interim: '', error: null };
    case 'stop':
      return state.phase === 'listening'
        ? { phase: 'idle', transcript: state.transcript, interim: '', error: null }
        : state;
    case 'result':
      return event.isFinal
        ? {
            phase: state.phase,
            transcript: appendFinalSegment(state.transcript, event.transcript),
            interim: '',
            error: state.error,
          }
        : { ...state, interim: event.transcript.trim() };
    case 'error':
      if (state.phase !== 'listening') {
        return state;
      }
      return SILENT_ERROR_REASONS.has(event.reason)
        ? { phase: 'idle', transcript: state.transcript, interim: '', error: null }
        : { phase: 'error', transcript: state.transcript, interim: '', error: event.reason };
    case 'reset':
      return state.phase === 'listening'
        ? state
        : INITIAL_SPEECH_RECOGNITION_STATE;
  }
}

/* Feature detection ------------------------------------------------------- */

/** Minimal structural typing of the Web Speech API surface used by the hook. */
export interface SpeechRecognitionInstanceLike {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  start(): void;
  stop(): void;
  abort(): void;
  onresult: ((event: {
    resultIndex: number;
    results: {
      length: number;
      [index: number]: { isFinal: boolean; [alternative: number]: { transcript: string } };
    };
  }) => void) | null;
  onerror: ((event: { error: string }) => void) | null;
  onend: (() => void) | null;
}

export type SpeechRecognitionCtor = new () => SpeechRecognitionInstanceLike;

export interface SpeechRecognitionCtorScope {
  SpeechRecognition?: SpeechRecognitionCtor | undefined;
  webkitSpeechRecognition?: SpeechRecognitionCtor | undefined;
}

/**
 * Resolves the browser constructor, preferring the standard property over the
 * webkit-prefixed one. Safe without a window (SSR / markup tests): returns null.
 */
export function getSpeechRecognitionConstructor(
  scope: SpeechRecognitionCtorScope | undefined = typeof window === 'undefined'
    ? undefined
    : (window as unknown as SpeechRecognitionCtorScope),
): SpeechRecognitionCtor | null {
  return scope?.SpeechRecognition ?? scope?.webkitSpeechRecognition ?? null;
}

/** Feature detect for the honest degradation: no button in browsers without support. */
export function isSpeechRecognitionSupported(): boolean {
  return getSpeechRecognitionConstructor() !== null;
}
