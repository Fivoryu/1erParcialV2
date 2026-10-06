import { useEffect, useRef, useState } from 'react';
import {
  INITIAL_SPEECH_RECOGNITION_STATE,
  SPEECH_RECOGNITION_LANG,
  getSpeechRecognitionConstructor,
  isSpeechRecognitionSupported,
  transitionSpeechRecognitionState,
  type SpeechRecognitionCtor,
  type SpeechRecognitionErrorReason,
  type SpeechRecognitionInstanceLike,
  type SpeechRecognitionState,
} from './speechRecognition';

export interface SpeechRecognitionControllerOptions {
  /** Injected feature detect, so tests can force supported/unsupported. */
  getConstructor: () => SpeechRecognitionCtor | null;
  /** Guarded read of the current machine state (hook keeps it in a ref). */
  read: () => SpeechRecognitionState;
  /** Guarded write of the next machine state (no writes after unmount, C21). */
  write: (next: SpeechRecognitionState) => void;
}

export interface SpeechRecognitionController {
  start(): void;
  stop(): void;
  reset(): void;
  /** Unmount cleanup: aborts without surfacing an error and detaches callbacks. */
  dispose(): void;
}

/** Maps a browser error.code onto the frozen reason codes (voz-asistente-ia.md §3). */
export function mapSpeechRecognitionError(code: string): SpeechRecognitionErrorReason {
  switch (code) {
    case 'no-speech':
      return 'no-speech';
    case 'aborted':
      return 'aborted';
    case 'not-allowed':
    case 'service-not-allowed':
      return 'not-allowed';
    case 'network':
      return 'network';
    case 'language-not-supported':
      return 'not-supported';
    default:
      return 'unknown';
  }
}

/**
 * React-free controller wrapping the Web Speech API through the pure machine
 * (same pattern as createUndoRedoController): testable without jsdom, with the
 * recognition instance injected via getConstructor.
 */
export function createSpeechRecognitionController({
  getConstructor,
  read,
  write,
}: SpeechRecognitionControllerOptions): SpeechRecognitionController {
  let recognition: SpeechRecognitionInstanceLike | null = null;

  const dispatch = (event: Parameters<typeof transitionSpeechRecognitionState>[1]): void => {
    write(transitionSpeechRecognitionState(read(), event));
  };

  return {
    start(): void {
      if (recognition !== null || read().phase === 'listening') {
        return;
      }
      const Ctor = getConstructor();
      if (Ctor === null) {
        return;
      }
      const instance = new Ctor();
      instance.continuous = false;
      instance.interimResults = true;
      instance.lang = SPEECH_RECOGNITION_LANG;
      instance.onresult = (event) => {
        for (let index = event.resultIndex; index < event.results.length; index += 1) {
          const result = event.results[index];
          if (!result) {
            continue;
          }
          dispatch({
            type: 'result',
            transcript: result[0]?.transcript ?? '',
            isFinal: result.isFinal,
          });
        }
      };
      instance.onerror = (event) => {
        dispatch({ type: 'error', reason: mapSpeechRecognitionError(event.error) });
      };
      instance.onend = () => {
        recognition = null;
        // After a silent error (no-speech/aborted) or a persistent one, the
        // machine is no longer listening, so this stop is a no-op there and
        // keeps the error for the UI; from listening it returns to idle.
        dispatch({ type: 'stop' });
      };
      recognition = instance;
      dispatch({ type: 'start' });
      instance.start();
    },
    stop(): void {
      recognition?.stop();
    },
    reset(): void {
      dispatch({ type: 'reset' });
    },
    dispose(): void {
      const instance = recognition;
      recognition = null;
      if (instance) {
        instance.onresult = null;
        instance.onerror = null;
        instance.onend = null;
        instance.abort();
      }
    },
  };
}

export interface UseSpeechRecognitionResult {
  supported: boolean;
  listening: boolean;
  transcript: string;
  interim: string;
  error: SpeechRecognitionErrorReason | null;
  start(): void;
  stop(): void;
  reset(): void;
}

/**
 * Wires the pure speech recognition machine into component state (V1 of the
 * voice increment; the mic button itself is V2 scope in AssistantPanel).
 */
export function useSpeechRecognition(): UseSpeechRecognitionResult {
  const [state, setState] = useState<SpeechRecognitionState>(INITIAL_SPEECH_RECOGNITION_STATE);
  const stateRef = useRef(state);
  const mountedRef = useRef(false);
  const controllerRef = useRef<SpeechRecognitionController | null>(null);

  if (controllerRef.current === null) {
    controllerRef.current = createSpeechRecognitionController({
      getConstructor: () => getSpeechRecognitionConstructor(),
      read: () => stateRef.current,
      write: (next) => {
        if (!mountedRef.current) {
          return;
        }
        stateRef.current = next;
        setState(next);
      },
    });
  }

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      // Abort without error: the browser fires onend/onerror('aborted'), but
      // the callbacks are detached and writes are guarded by mountedRef.
      controllerRef.current?.dispose();
    };
  }, []);

  return {
    supported: isSpeechRecognitionSupported(),
    listening: state.phase === 'listening',
    transcript: state.transcript,
    interim: state.interim,
    error: state.error,
    start: () => {
      controllerRef.current?.start();
    },
    stop: () => {
      controllerRef.current?.stop();
    },
    reset: () => {
      controllerRef.current?.reset();
    },
  };
}
