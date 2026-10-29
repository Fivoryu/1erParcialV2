import { describe, expect, it } from 'vitest';
import {
  INITIAL_SPEECH_RECOGNITION_STATE,
  SPEECH_RECOGNITION_LANG,
  getSpeechRecognitionConstructor,
  speechRecognitionErrorMessage,
  isSpeechRecognitionSupported,
  transitionSpeechRecognitionState,
  type SpeechRecognitionMachineEvent,
  type SpeechRecognitionState,
} from './speechRecognition';

function transitioned(state: SpeechRecognitionState, event: SpeechRecognitionMachineEvent): SpeechRecognitionState {
  return transitionSpeechRecognitionState(state, event);
}

describe('speech recognition constants', () => {
  it('freezes the dictation language to es-AR', () => {
    expect(SPEECH_RECOGNITION_LANG).toBe('es-AR');
  });

  it('starts idle with empty transcript, empty interim and no error', () => {
    expect(INITIAL_SPEECH_RECOGNITION_STATE).toEqual({
      phase: 'idle',
      transcript: '',
      interim: '',
      error: null,
    });
  });
});

describe('transitionSpeechRecognitionState — start', () => {
  it('moves idle to listening and clears interim and error while keeping the transcript', () => {
    const state: SpeechRecognitionState = {
      phase: 'idle',
      transcript: 'texto previo',
      interim: 'parcial',
      error: null,
    };
    expect(transitioned(state, { type: 'start' })).toEqual({
      phase: 'listening',
      transcript: 'texto previo',
      interim: '',
      error: null,
    });
  });

  it('moves error to listening (recovery path)', () => {
    const state: SpeechRecognitionState = {
      phase: 'error',
      transcript: '',
      interim: '',
      error: 'not-allowed',
    };
    expect(transitioned(state, { type: 'start' }).phase).toBe('listening');
    expect(transitioned(state, { type: 'start' }).error).toBeNull();
  });

  it('is a no-op while already listening', () => {
    const state: SpeechRecognitionState = { phase: 'listening', transcript: '', interim: '', error: null };
    expect(transitioned(state, { type: 'start' })).toBe(state);
  });
});

describe('transitionSpeechRecognitionState — stop', () => {
  it('moves listening to idle keeping the final transcript', () => {
    const state: SpeechRecognitionState = {
      phase: 'listening',
      transcript: 'hola mundo',
      interim: '',
      error: null,
    };
    expect(transitioned(state, { type: 'stop' })).toEqual({
      phase: 'idle',
      transcript: 'hola mundo',
      interim: '',
      error: null,
    });
  });

  it('clears the pending interim on stop but never the transcript', () => {
    const state: SpeechRecognitionState = {
      phase: 'listening',
      transcript: 'hola',
      interim: 'mun',
      error: null,
    };
    expect(transitioned(state, { type: 'stop' })).toEqual({
      phase: 'idle',
      transcript: 'hola',
      interim: '',
      error: null,
    });
  });

  it('is a no-op when not listening', () => {
    const idle: SpeechRecognitionState = { phase: 'idle', transcript: '', interim: '', error: null };
    const error: SpeechRecognitionState = { phase: 'error', transcript: '', interim: '', error: 'network' };
    expect(transitioned(idle, { type: 'stop' })).toBe(idle);
    expect(transitioned(error, { type: 'stop' })).toBe(error);
  });
});

describe('transitionSpeechRecognitionState — result', () => {
  it('appends a final segment to the transcript with a single space separator', () => {
    const state: SpeechRecognitionState = {
      phase: 'listening',
      transcript: 'hola',
      interim: '',
      error: null,
    };
    expect(transitioned(state, { type: 'result', transcript: 'mundo', isFinal: true })).toEqual({
      phase: 'listening',
      transcript: 'hola mundo',
      interim: '',
      error: null,
    });
  });

  it('trims each final segment and skips empty ones', () => {
    let state: SpeechRecognitionState = {
      phase: 'listening',
      transcript: '',
      interim: '',
      error: null,
    };
    state = transitioned(state, { type: 'result', transcript: '  hola  ', isFinal: true });
    expect(state.transcript).toBe('hola');
    state = transitioned(state, { type: 'result', transcript: '   ', isFinal: true });
    expect(state.transcript).toBe('hola');
  });

  it('holds interim text separately and never mixes it into the final transcript', () => {
    let state: SpeechRecognitionState = {
      phase: 'listening',
      transcript: '',
      interim: '',
      error: null,
    };
    state = transitioned(state, { type: 'result', transcript: 'hol', isFinal: false });
    expect(state.interim).toBe('hol');
    expect(state.transcript).toBe('');
    state = transitioned(state, { type: 'result', transcript: 'hola', isFinal: true });
    expect(state.transcript).toBe('hola');
    expect(state.interim).toBe('');
  });

  it('replaces the previous interim with the newest interim segment', () => {
    let state: SpeechRecognitionState = {
      phase: 'listening',
      transcript: '',
      interim: '',
      error: null,
    };
    state = transitioned(state, { type: 'result', transcript: 'hol', isFinal: false });
    state = transitioned(state, { type: 'result', transcript: 'hola ', isFinal: false });
    expect(state.interim).toBe('hola');
    expect(state.transcript).toBe('');
  });
});

describe('transitionSpeechRecognitionState — error', () => {
  it('moves listening to error with the reason code for a persistent failure', () => {
    const state: SpeechRecognitionState = {
      phase: 'listening',
      transcript: 'parcial',
      interim: 'x',
      error: null,
    };
    expect(transitioned(state, { type: 'error', reason: 'not-allowed' })).toEqual({
      phase: 'error',
      transcript: 'parcial',
      interim: '',
      error: 'not-allowed',
    });
  });

  it('treats no-speech as silent: back to idle with no error and the transcript kept', () => {
    const state: SpeechRecognitionState = {
      phase: 'listening',
      transcript: 'hola',
      interim: 'mu',
      error: null,
    };
    expect(transitioned(state, { type: 'error', reason: 'no-speech' })).toEqual({
      phase: 'idle',
      transcript: 'hola',
      interim: '',
      error: null,
    });
  });

  it('treats aborted as silent: back to idle with no error and the transcript kept', () => {
    const state: SpeechRecognitionState = {
      phase: 'listening',
      transcript: 'hola',
      interim: '',
      error: null,
    };
    expect(transitioned(state, { type: 'error', reason: 'aborted' })).toEqual({
      phase: 'idle',
      transcript: 'hola',
      interim: '',
      error: null,
    });
  });

  it('is a no-op for silent reasons when not listening', () => {
    const idle: SpeechRecognitionState = { phase: 'idle', transcript: '', interim: '', error: null };
    expect(transitioned(idle, { type: 'error', reason: 'no-speech' })).toBe(idle);
  });

  it('is a no-op for persistent reasons when not listening', () => {
    const idle: SpeechRecognitionState = { phase: 'idle', transcript: '', interim: '', error: null };
    expect(transitioned(idle, { type: 'error', reason: 'network' })).toBe(idle);
  });
});

describe('transitionSpeechRecognitionState — reset', () => {
  it('moves error to idle clearing transcript and error', () => {
    const state: SpeechRecognitionState = {
      phase: 'error',
      transcript: 'texto viejo',
      interim: '',
      error: 'network',
    };
    expect(transitioned(state, { type: 'reset' })).toEqual(INITIAL_SPEECH_RECOGNITION_STATE);
  });

  it('clears the transcript from idle too', () => {
    const state: SpeechRecognitionState = {
      phase: 'idle',
      transcript: 'texto',
      interim: '',
      error: null,
    };
    expect(transitioned(state, { type: 'reset' })).toEqual(INITIAL_SPEECH_RECOGNITION_STATE);
  });

  it('is a no-op while listening (stop first)', () => {
    const state: SpeechRecognitionState = {
      phase: 'listening',
      transcript: 'texto',
      interim: '',
      error: null,
    };
    expect(transitioned(state, { type: 'reset' })).toBe(state);
  });
});

describe('feature detection', () => {
  it('reports unsupported when the scope has no SpeechRecognition constructor', () => {
    expect(getSpeechRecognitionConstructor({})).toBeNull();
    expect(getSpeechRecognitionConstructor({ SpeechRecognition: undefined, webkitSpeechRecognition: undefined })).toBeNull();
  });

  it('prefers SpeechRecognition over webkitSpeechRecognition', () => {
    const standard = class {};
    const webkit = class {};
    expect(
      getSpeechRecognitionConstructor({ SpeechRecognition: standard as never, webkitSpeechRecognition: webkit as never }),
    ).toBe(standard);
  });

  it('falls back to webkitSpeechRecognition', () => {
    const webkit = class {};
    expect(getSpeechRecognitionConstructor({ webkitSpeechRecognition: webkit as never })).toBe(webkit);
  });

  it('isSpeechRecognitionSupported is safe when window is undefined (SSR / markup tests)', () => {
    // This suite runs in the Node environment: there is no global window.
    expect(typeof globalThis.window).toBe('undefined');
    expect(isSpeechRecognitionSupported()).toBe(false);
  });
});

describe('speechRecognitionErrorMessage', () => {
  it('explains every visible dictation failure in Spanish', () => {
    expect(speechRecognitionErrorMessage('not-allowed')).toBe('Permite el acceso al micrófono para dictar.');
    expect(speechRecognitionErrorMessage('not-supported')).toBe('Este navegador no admite el dictado en español.');
    expect(speechRecognitionErrorMessage('network')).toBe('El dictado necesita conexión a internet. Revisa la red e inténtalo de nuevo.');
    expect(speechRecognitionErrorMessage('unknown')).toBe('No se pudo usar el dictado. Inténtalo de nuevo.');
  });

  it('stays silent when nobody spoke or the user stopped', () => {
    expect(speechRecognitionErrorMessage('no-speech')).toBeNull();
    expect(speechRecognitionErrorMessage('aborted')).toBeNull();
    expect(speechRecognitionErrorMessage(null)).toBeNull();
  });
});
