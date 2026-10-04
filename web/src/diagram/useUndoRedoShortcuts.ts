import { useEffect, useRef, useState } from 'react';
import { createUndoRedoApi, type UndoRedoApi } from '../api/undoRedo';
import type { Uuid } from '../api/types';

// IA4-06 (trimmed scope): keyboard undo/redo shortcuts plus canvas Escape only.
// Alignment, distribution, and grid snapping are declared debt for a later increment.

export type UndoRedoShortcutAction = 'undo' | 'redo' | 'escape';

export interface UndoRedoKeyEvent {
  key: string;
  ctrlKey: boolean;
  metaKey: boolean;
  altKey: boolean;
  shiftKey: boolean;
  repeat: boolean;
  preventDefault(): void;
}

export interface UndoRedoHints {
  canUndo: boolean;
  canRedo: boolean;
  busy: boolean;
}

const INITIAL_HINTS: UndoRedoHints = { canUndo: true, canRedo: true, busy: false };

interface TextEntryTarget {
  isContentEditable?: boolean;
  tagName?: string;
}

/** In the DOM this is EventTarget; tests pass duck-typed stand-ins. */
export type UndoRedoEventTarget = EventTarget | TextEntryTarget;

function isTextEntryTarget(target: UndoRedoEventTarget | null): boolean {
  if (typeof target !== 'object' || target === null) {
    return false;
  }
  const candidate = target as TextEntryTarget;
  if (candidate.isContentEditable === true) {
    return true;
  }
  const tagName = typeof candidate.tagName === 'string' ? candidate.tagName.toUpperCase() : '';
  return tagName === 'INPUT' || tagName === 'TEXTAREA' || tagName === 'SELECT';
}

/**
 * Frozen shortcut set: Ctrl+Z undo; Ctrl+Y and Ctrl+Shift+Z redo; bare Escape clears the
 * selection. Meta/Alt combos stay reserved for the browser, and text-entry targets are
 * never intercepted.
 */
export function undoRedoShortcutAction(
  event: Pick<UndoRedoKeyEvent, 'key' | 'ctrlKey' | 'metaKey' | 'altKey' | 'shiftKey' | 'repeat'>,
  target: UndoRedoEventTarget | null,
): UndoRedoShortcutAction | null {
  if (event.repeat || isTextEntryTarget(target)) {
    return null;
  }
  if (event.metaKey || event.altKey) {
    return null;
  }
  if (event.key === 'Escape') {
    return event.ctrlKey ? null : 'escape';
  }
  if (!event.ctrlKey) {
    return null;
  }
  const key = event.key.toLowerCase();
  if (key === 'z') {
    return event.shiftKey ? 'redo' : 'undo';
  }
  if (key === 'y') {
    return 'redo';
  }
  return null;
}

export interface UndoRedoControllerOptions {
  projectId: Uuid | undefined;
  api: Pick<UndoRedoApi, 'undo' | 'redo'>;
  onApplied?: () => void;
  onError?: (cause: unknown) => void;
  onEscape?: () => void;
  onHintsChange?: (hints: UndoRedoHints) => void;
}

export interface UndoRedoController {
  hints(): UndoRedoHints;
  undo(): void;
  redo(): void;
  handleKeyDown(event: UndoRedoKeyEvent, target: UndoRedoEventTarget | null): void;
  dispose(): void;
}

export function createUndoRedoController(options: UndoRedoControllerOptions): UndoRedoController {
  let disposed = false;
  let busy = false;
  let canUndo = true;
  let canRedo = true;

  function emit(): void {
    if (!disposed) {
      options.onHintsChange?.({ canUndo, canRedo, busy });
    }
  }

  function perform(action: 'undo' | 'redo'): void {
    if (disposed || busy || !options.projectId) {
      return;
    }
    busy = true;
    emit();
    const request = action === 'undo'
      ? options.api.undo(options.projectId)
      : options.api.redo(options.projectId);
    void request
      .then((result) => {
        if (disposed) {
          return;
        }
        if (result.status === 'ACCEPTED') {
          // Best-effort per-session hint: ACCEPTED keeps both actions enabled because more
          // entries may remain; the server stays authoritative and disables on NOTHING_TO_*.
          canUndo = true;
          canRedo = true;
          options.onApplied?.();
        } else if (result.code === 'NOTHING_TO_UNDO') {
          canUndo = false;
        } else if (result.code === 'NOTHING_TO_REDO') {
          canRedo = false;
        }
      })
      .catch((cause: unknown) => {
        if (!disposed) {
          options.onError?.(cause);
        }
      })
      .finally(() => {
        if (disposed) {
          return;
        }
        busy = false;
        emit();
      });
  }

  return {
    hints() {
      return { canUndo, canRedo, busy };
    },
    undo() {
      perform('undo');
    },
    redo() {
      perform('redo');
    },
    handleKeyDown(event, target) {
      const action = undoRedoShortcutAction(event, target);
      if (action === null) {
        return;
      }
      if (action === 'escape') {
        options.onEscape?.();
        return;
      }
      event.preventDefault();
      perform(action);
    },
    dispose() {
      disposed = true;
    },
  };
}

const defaultApi: Pick<UndoRedoApi, 'undo' | 'redo'> = createUndoRedoApi();

export interface UndoRedoShortcutsOptions {
  projectId: Uuid | undefined;
  onApplied?: (() => void) | undefined;
  onError?: ((cause: unknown) => void) | undefined;
  onEscape?: (() => void) | undefined;
  api?: (Pick<UndoRedoApi, 'undo' | 'redo'>) | undefined;
}

export type UndoRedoShortcutsHandle = UndoRedoHints & {
  undo(): void;
  redo(): void;
};

/**
 * Page-level undo/redo keyboard shortcuts and Escape. Only active while a project is open;
 * the latest option values are always read through a ref so the listeners stay stable.
 */
export function useUndoRedoShortcuts(options: UndoRedoShortcutsOptions): UndoRedoShortcutsHandle {
  const [hints, setHints] = useState<UndoRedoHints>(INITIAL_HINTS);
  const mountedRef = useRef(false);
  const optionsRef = useRef(options);
  const controllerRef = useRef<UndoRedoController | null>(null);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    optionsRef.current = options;
  });

  const projectId = options.projectId;
  useEffect(() => {
    const controller = createUndoRedoController({
      projectId,
      api: optionsRef.current.api ?? defaultApi,
      onApplied: () => optionsRef.current.onApplied?.(),
      onError: (cause) => optionsRef.current.onError?.(cause),
      onEscape: () => optionsRef.current.onEscape?.(),
      onHintsChange: (next) => {
        if (mountedRef.current) {
          setHints(next);
        }
      },
    });
    controllerRef.current = controller;
    return () => {
      controller.dispose();
      controllerRef.current = null;
    };
  }, [projectId]);

  useEffect(() => {
    if (!projectId) {
      return undefined;
    }
    const handleKeyDown = (event: KeyboardEvent): void => {
      controllerRef.current?.handleKeyDown(event, event.target);
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [projectId]);

  return {
    ...hints,
    undo() {
      controllerRef.current?.undo();
    },
    redo() {
      controllerRef.current?.redo();
    },
  };
}
