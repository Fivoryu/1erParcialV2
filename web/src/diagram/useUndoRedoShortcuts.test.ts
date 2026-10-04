import { describe, expect, it, vi } from 'vitest';
import type { UndoRedoResult } from '../api/undoRedo';
import type { Uuid } from '../api/types';
import {
  createUndoRedoController,
  undoRedoShortcutAction,
  type UndoRedoHints,
  type UndoRedoKeyEvent,
} from './useUndoRedoShortcuts';

const projectId = '22222222-2222-4222-8222-222222222222';

function keyEvent(overrides: Partial<UndoRedoKeyEvent> = {}): UndoRedoKeyEvent {
  return {
    key: 'z',
    ctrlKey: true,
    metaKey: false,
    altKey: false,
    shiftKey: false,
    repeat: false,
    preventDefault: () => undefined,
    ...overrides,
  };
}

function acceptedResult(): UndoRedoResult {
  return {
    status: 'ACCEPTED',
    code: null,
    message: null,
    operationId: '33333333-3333-4333-8333-333333333333',
    resultRevision: 9,
  };
}

function nothingToUndoResult(): UndoRedoResult {
  return {
    status: 'REJECTED',
    code: 'NOTHING_TO_UNDO',
    message: 'No hay acciones para deshacer.',
    operationId: null,
    resultRevision: null,
  };
}

function nothingToRedoResult(): UndoRedoResult {
  return {
    status: 'REJECTED',
    code: 'NOTHING_TO_REDO',
    message: 'No hay acciones para rehacer.',
    operationId: null,
    resultRevision: null,
  };
}

function pendingResult(): {
  promise: Promise<UndoRedoResult>;
  resolve: (result: UndoRedoResult) => void;
  reject: (cause: unknown) => void;
} {
  let resolve!: (result: UndoRedoResult) => void;
  let reject!: (cause: unknown) => void;
  const promise = new Promise<UndoRedoResult>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function controllerHarness(projectIdValue: Uuid | undefined = projectId) {
  const api = {
    undo: vi.fn<(projectId: Uuid) => Promise<UndoRedoResult>>(),
    redo: vi.fn<(projectId: Uuid) => Promise<UndoRedoResult>>(),
  };
  api.undo.mockResolvedValue(acceptedResult());
  api.redo.mockResolvedValue(acceptedResult());
  const onApplied = vi.fn();
  const onError = vi.fn();
  const onEscape = vi.fn();
  const hintLog: UndoRedoHints[] = [];
  const controller = createUndoRedoController({
    projectId: projectIdValue,
    api,
    onApplied,
    onError,
    onEscape,
    onHintsChange: (hints) => hintLog.push(hints),
  });
  return { api, onApplied, onError, onEscape, hintLog, controller };
}

async function settle(): Promise<void> {
  // The controller continuation runs through then/catch/finally microtasks; hop a macrotask twice.
  await new Promise((resolve) => setTimeout(resolve, 0));
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('undoRedoShortcutAction', () => {
  it('maps Ctrl+Z outside a text entry to undo', () => {
    expect(undoRedoShortcutAction(keyEvent(), null)).toBe('undo');
  });

  it('ignores Ctrl+Z when the target is an input, textarea, select, or contentEditable', () => {
    const targets = [
      { tagName: 'INPUT' },
      { tagName: 'TEXTAREA' },
      { tagName: 'SELECT' },
      { tagName: 'DIV', isContentEditable: true },
    ];
    for (const target of targets) {
      expect(undoRedoShortcutAction(keyEvent(), target)).toBeNull();
    }
  });

  it('maps Ctrl+Shift+Z and Ctrl+Y to redo', () => {
    expect(undoRedoShortcutAction(keyEvent({ shiftKey: true }), null)).toBe('redo');
    expect(undoRedoShortcutAction(keyEvent({ key: 'y' }), null)).toBe('redo');
    expect(undoRedoShortcutAction(keyEvent({ key: 'Y' }), null)).toBe('redo');
  });

  it('ignores combos outside the frozen set: meta, alt, no modifier, unrelated keys, repeat', () => {
    expect(undoRedoShortcutAction(keyEvent({ metaKey: true }), null)).toBeNull();
    expect(undoRedoShortcutAction(keyEvent({ altKey: true }), null)).toBeNull();
    expect(undoRedoShortcutAction(keyEvent({ ctrlKey: false }), null)).toBeNull();
    expect(undoRedoShortcutAction(keyEvent({ key: 'k' }), null)).toBeNull();
    expect(undoRedoShortcutAction(keyEvent({ repeat: true }), null)).toBeNull();
  });

  it('maps bare Escape to escape but keeps modified Escape reserved', () => {
    expect(undoRedoShortcutAction(keyEvent({ key: 'Escape', ctrlKey: false }), null)).toBe('escape');
    expect(undoRedoShortcutAction(keyEvent({ key: 'Escape' }), null)).toBeNull();
  });
});

describe('undo/redo controller', () => {
  it('performing undo calls the API with the project id, applies onApplied and reports busy transitions on ACCEPTED', async () => {
    const { api, onApplied, hintLog, controller } = controllerHarness();
    const pending = pendingResult();
    api.undo.mockReturnValue(pending.promise);

    controller.undo();

    expect(api.undo).toHaveBeenCalledWith(projectId);
    expect(hintLog[0]).toEqual({ canUndo: true, canRedo: true, busy: true });

    pending.resolve(acceptedResult());
    await vi.waitFor(() => expect(onApplied).toHaveBeenCalledTimes(1));

    expect(hintLog.at(-1)).toEqual({ canUndo: true, canRedo: true, busy: false });
  });

  it('a NOTHING_TO_UNDO rejection disables the undo hint without calling onApplied', async () => {
    const { api, onApplied, hintLog, controller } = controllerHarness();
    api.undo.mockResolvedValue(nothingToUndoResult());

    controller.undo();
    await vi.waitFor(() => expect(hintLog.length).toBeGreaterThan(1));

    expect(onApplied).not.toHaveBeenCalled();
    expect(hintLog.at(-1)).toEqual({ canUndo: false, canRedo: true, busy: false });
  });

  it('a NOTHING_TO_REDO rejection disables the redo hint', async () => {
    const { api, hintLog, controller } = controllerHarness();
    api.redo.mockResolvedValue(nothingToRedoResult());

    controller.redo();
    await vi.waitFor(() => expect(hintLog.length).toBeGreaterThan(1));

    expect(hintLog.at(-1)).toEqual({ canUndo: true, canRedo: false, busy: false });
  });

  it('a transport failure reports the error and releases the busy flag', async () => {
    const { api, onApplied, onError, hintLog, controller } = controllerHarness();
    const pending = pendingResult();
    api.redo.mockReturnValue(pending.promise);

    controller.redo();
    pending.reject(new Error('The server could not be reached.'));
    await vi.waitFor(() => expect(onError).toHaveBeenCalledTimes(1));

    expect(onApplied).not.toHaveBeenCalled();
    expect(hintLog.at(-1)).toEqual({ canUndo: true, canRedo: true, busy: false });
  });

  it('ignores a second request while one is in flight', async () => {
    const { api, controller } = controllerHarness();
    const pending = pendingResult();
    api.undo.mockReturnValue(pending.promise);

    controller.undo();
    controller.undo();

    expect(api.undo).toHaveBeenCalledTimes(1);
    pending.resolve(acceptedResult());
    await settle();
  });

  it('does nothing without a project id', () => {
    const api = {
      undo: vi.fn<(projectId: Uuid) => Promise<UndoRedoResult>>(),
      redo: vi.fn<(projectId: Uuid) => Promise<UndoRedoResult>>(),
    };
    const controller = createUndoRedoController({ projectId: undefined, api });

    controller.undo();
    controller.redo();

    expect(api.undo).not.toHaveBeenCalled();
    expect(api.redo).not.toHaveBeenCalled();
  });

  it('Ctrl+Z keydown triggers undo and preventDefault; text-entry targets and other keys do nothing', () => {
    const { api, controller } = controllerHarness();
    const preventDefault = vi.fn();

    controller.handleKeyDown(keyEvent({ preventDefault }), null);

    expect(api.undo).toHaveBeenCalledTimes(1);
    expect(preventDefault).toHaveBeenCalledTimes(1);

    controller.handleKeyDown(keyEvent(), { tagName: 'TEXTAREA' });
    expect(api.undo).toHaveBeenCalledTimes(1);

    controller.handleKeyDown(keyEvent({ ctrlKey: false }), null);
    expect(api.undo).toHaveBeenCalledTimes(1);
  });

  it('bare Escape keydown calls onEscape without touching the API', () => {
    const { api, onEscape, controller } = controllerHarness();

    controller.handleKeyDown(keyEvent({ key: 'Escape', ctrlKey: false }), null);

    expect(onEscape).toHaveBeenCalledTimes(1);
    expect(api.undo).not.toHaveBeenCalled();
    expect(api.redo).not.toHaveBeenCalled();
  });

  it('drops the late result after dispose: no onApplied and no hint writes', async () => {
    const { api, onApplied, hintLog, controller } = controllerHarness();
    const pending = pendingResult();
    api.undo.mockReturnValue(pending.promise);

    controller.undo();
    controller.dispose();
    pending.resolve(acceptedResult());
    await settle();

    expect(onApplied).not.toHaveBeenCalled();
    expect(hintLog).toEqual([{ canUndo: true, canRedo: true, busy: true }]);
  });
});
