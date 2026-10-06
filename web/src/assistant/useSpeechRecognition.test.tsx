import { describe, expect, it } from 'vitest';
import {
  INITIAL_SPEECH_RECOGNITION_STATE,
  SPEECH_RECOGNITION_LANG,
  type SpeechRecognitionErrorReason,
  type SpeechRecognitionInstanceLike,
  type SpeechRecognitionState,
} from './speechRecognition';
import {
  createSpeechRecognitionController,
  mapSpeechRecognitionError,
  type SpeechRecognitionController,
} from './useSpeechRecognition';

/**
 * The repo convention is pure-logic tests without jsdom or real DOM events
 * (see useUndoRedoShortcuts.test.ts): the controller is exercised directly and
 * the browser callbacks are emitted by hand through a fake recognition instance.
 */
class FakeSpeechRecognition implements SpeechRecognitionInstanceLike {
  continuous = false;
  interimResults = false;
  lang = '';
  onresult: SpeechRecognitionInstanceLike['onresult'] = null;
  onerror: SpeechRecognitionInstanceLike['onerror'] = null;
  onend: SpeechRecognitionInstanceLike['onend'] = null;
  started = false;
  stopped = false;
  abortedCount = 0;

  start(): void {
    this.started = true;
  }

  stop(): void {
    this.stopped = true;
  }

  abort(): void {
    this.abortedCount += 1;
  }

  emitResult(
    segments: Array<{ transcript: string; isFinal: boolean }>,
    resultIndex = 0,
  ): void {
    this.onresult?.({
      resultIndex,
      results: {
        length: segments.length,
        ...Object.fromEntries(
          segments.map((segment, index) => [
            index,
            { isFinal: segment.isFinal, 0: { transcript: segment.transcript } },
          ]),
        ),
      },
    });
  }

  emitError(code: string): void {
    this.onerror?.({ error: code });
  }

  emitEnd(): void {
    this.onend?.();
  }
}

interface Harness {
  controller: SpeechRecognitionController;
  getState: () => SpeechRecognitionState;
  written: SpeechRecognitionState[];
  instances: FakeSpeechRecognition[];
  lastInstance: () => FakeSpeechRecognition;
}

function controllerHarness(supported = true): Harness {
  const instances: FakeSpeechRecognition[] = [];
  const Ctor = class extends FakeSpeechRecognition {
    constructor() {
      super();
      instances.push(this);
    }
  };
  let state: SpeechRecognitionState = INITIAL_SPEECH_RECOGNITION_STATE;
  const written: SpeechRecognitionState[] = [];
  const controller = createSpeechRecognitionController({
    getConstructor: () => (supported ? (Ctor as unknown as new () => SpeechRecognitionInstanceLike) : null),
    read: () => state,
    write: (next) => {
      written.push(next);
      state = next;
    },
  });
  return {
    controller,
    getState: () => state,
    written,
    instances,
    lastInstance: () => {
      const last = instances.at(-1);
      if (!last) {
        throw new Error('no recognition instance was created');
      }
      return last;
    },
  };
}

describe('mapSpeechRecognitionError', () => {
  it('maps browser error codes onto the frozen reason codes', () => {
    expect(mapSpeechRecognitionError('not-allowed')).toBe<SpeechRecognitionErrorReason>('not-allowed');
    expect(mapSpeechRecognitionError('service-not-allowed')).toBe<SpeechRecognitionErrorReason>('not-allowed');
    expect(mapSpeechRecognitionError('no-speech')).toBe<SpeechRecognitionErrorReason>('no-speech');
    expect(mapSpeechRecognitionError('aborted')).toBe<SpeechRecognitionErrorReason>('aborted');
    expect(mapSpeechRecognitionError('network')).toBe<SpeechRecognitionErrorReason>('network');
    expect(mapSpeechRecognitionError('language-not-supported')).toBe<SpeechRecognitionErrorReason>('not-supported');
  });

  it('maps every unrecognized browser code to unknown', () => {
    expect(mapSpeechRecognitionError('audio-capture')).toBe<SpeechRecognitionErrorReason>('unknown');
    expect(mapSpeechRecognitionError('algo-raro')).toBe<SpeechRecognitionErrorReason>('unknown');
  });
});

describe('speech recognition controller — start', () => {
  it('creates one recognition instance configured for es-AR dictation with interim results', () => {
    const { controller, getState, instances, lastInstance } = controllerHarness();

    controller.start();

    expect(instances).toHaveLength(1);
    const instance = lastInstance();
    expect(instance.continuous).toBe(false);
    expect(instance.interimResults).toBe(true);
    expect(instance.lang).toBe(SPEECH_RECOGNITION_LANG);
    expect(instance.started).toBe(true);
    expect(getState().phase).toBe('listening');
  });

  it('is a no-op in an unsupported browser: no instance and no state change', () => {
    const { controller, getState, written } = controllerHarness(false);

    controller.start();

    expect(getState()).toEqual(INITIAL_SPEECH_RECOGNITION_STATE);
    expect(written).toHaveLength(0);
  });

  it('ignores a second start while listening', () => {
    const { controller, instances } = controllerHarness();

    controller.start();
    controller.start();

    expect(instances).toHaveLength(1);
  });

  it('can restart after stop and after error, keeping the accumulated transcript', () => {
    const { controller, getState, lastInstance } = controllerHarness();

    controller.start();
    lastInstance().emitResult([{ transcript: 'hola', isFinal: true }]);
    lastInstance().emitEnd();
    expect(getState().phase).toBe('idle');

    controller.start();
    expect(getState().phase).toBe('listening');
    expect(getState().transcript).toBe('hola');

    lastInstance().emitError('network');
    // The Web Speech API always fires onend after onerror; it clears the
    // stale instance so a later start can create a fresh one.
    lastInstance().emitEnd();
    expect(getState().phase).toBe('error');
    controller.start();
    expect(getState().phase).toBe('listening');
    expect(getState().transcript).toBe('hola');
  });
});

describe('speech recognition controller — happy path', () => {
  it('start -> final results -> stop leaves the final transcript and clears interim', () => {
    const { controller, getState, lastInstance } = controllerHarness();

    controller.start();
    lastInstance().emitResult([{ transcript: 'creá una clase', isFinal: false }]);
    // Final event: the browser re-sends the same result as final (resultIndex 0)
    // plus a new interim segment at index 1.
    lastInstance().emitResult([
      { transcript: 'creá una clase', isFinal: true },
      { transcript: 'llamada', isFinal: false },
    ]);
    controller.stop();
    lastInstance().emitEnd();

    expect(getState()).toEqual({
      phase: 'idle',
      // The trailing 'llamada' segment was interim-only: it never leaks here.
      transcript: 'creá una clase',
      interim: '',
      error: null,
    });
    expect(lastInstance().stopped).toBe(true);
  });

  it('interim text never leaks into the final transcript', () => {
    const { controller, getState, lastInstance } = controllerHarness();

    controller.start();
    lastInstance().emitResult([{ transcript: 'hol', isFinal: false }]);
    expect(getState().interim).toBe('hol');
    expect(getState().transcript).toBe('');

    lastInstance().emitResult([{ transcript: 'hola', isFinal: true }]);
    expect(getState().transcript).toBe('hola');
    expect(getState().interim).toBe('');

    lastInstance().emitEnd();
    expect(getState().transcript).toBe('hola');
    expect(getState().interim).toBe('');
  });

  it('accumulates multiple final segments with single-space separators', () => {
    const { controller, getState, lastInstance } = controllerHarness();

    controller.start();
    for (const segment of ['uno', 'dos', 'tres']) {
      lastInstance().emitResult([{ transcript: segment, isFinal: true }]);
    }

    expect(getState().transcript).toBe('uno dos tres');
  });

  it('clears a pending interim when the user stops', () => {
    const { controller, getState, lastInstance } = controllerHarness();

    controller.start();
    lastInstance().emitResult([{ transcript: 'par', isFinal: false }]);
    controller.stop();
    lastInstance().emitEnd();

    expect(getState().interim).toBe('');
    expect(getState().transcript).toBe('');
  });
});

describe('speech recognition controller — errors', () => {
  it('no-speech is silent: back to idle, no persistent error, transcript kept', () => {
    const { controller, getState, lastInstance } = controllerHarness();

    controller.start();
    lastInstance().emitResult([{ transcript: 'hola', isFinal: true }]);
    lastInstance().emitError('no-speech');
    lastInstance().emitEnd();

    expect(getState()).toEqual({
      phase: 'idle',
      transcript: 'hola',
      interim: '',
      error: null,
    });
  });

  it('aborted is silent: back to idle, no persistent error, transcript kept', () => {
    const { controller, getState, lastInstance } = controllerHarness();

    controller.start();
    lastInstance().emitResult([{ transcript: 'hola', isFinal: true }]);
    lastInstance().emitError('aborted');
    lastInstance().emitEnd();

    expect(getState()).toEqual({
      phase: 'idle',
      transcript: 'hola',
      interim: '',
      error: null,
    });
  });

  it('not-allowed maps to a persistent error with its reason and survives onend', () => {
    const { controller, getState, lastInstance } = controllerHarness();

    controller.start();
    lastInstance().emitResult([{ transcript: 'hola', isFinal: true }]);
    lastInstance().emitError('not-allowed');
    lastInstance().emitEnd();

    expect(getState()).toEqual({
      phase: 'error',
      transcript: 'hola',
      interim: '',
      error: 'not-allowed',
    });
  });

  it('network maps to a persistent error with its reason', () => {
    const { controller, getState, lastInstance } = controllerHarness();

    controller.start();
    lastInstance().emitError('network');

    expect(getState().phase).toBe('error');
    expect(getState().error).toBe('network');
  });
});

describe('speech recognition controller — reset', () => {
  it('clears transcript and error after a persistent failure', () => {
    const { controller, getState, lastInstance } = controllerHarness();

    controller.start();
    lastInstance().emitResult([{ transcript: 'texto', isFinal: true }]);
    lastInstance().emitError('not-allowed');
    controller.reset();

    expect(getState()).toEqual(INITIAL_SPEECH_RECOGNITION_STATE);
  });
});

describe('speech recognition controller — dispose', () => {
  it('aborts without writing any state (unmount cleanup)', () => {
    const { controller, getState, lastInstance } = controllerHarness();

    controller.start();
    lastInstance().emitResult([{ transcript: 'hola', isFinal: true }]);
    const instance = lastInstance();

    controller.dispose();

    expect(instance.abortedCount).toBe(1);
    expect(getState().phase).toBe('listening');
    expect(getState().transcript).toBe('hola');
  });

  it('detaches the browser callbacks before aborting: late events write nothing', () => {
    const { controller, getState, lastInstance, written } = controllerHarness();

    controller.start();
    lastInstance().emitResult([{ transcript: 'hola', isFinal: true }]);
    const writesBeforeDispose = written.length;

    controller.dispose();
    lastInstance().emitResult([{ transcript: 'tarde', isFinal: true }]);
    lastInstance().emitError('network');
    lastInstance().emitEnd();

    expect(written).toHaveLength(writesBeforeDispose);
    expect(getState().transcript).toBe('hola');
  });
});
