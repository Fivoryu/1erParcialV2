import { useEffect, useRef, useState } from 'react';
import type { AssistantApi } from '../api/assistant';
import { createAssistantApi } from '../api/assistant';
import type { Uuid } from '../api/types';
import { createAssistantController, type AssistantController } from './assistantController';
import { canConfirm, type AssistantState } from './assistantState';

export interface UseAssistantPanelOptions {
  projectId: Uuid | undefined;
  /** Live model revision; a change between plan and confirm marks the preview stale. */
  currentRevision: number | null;
  /** Optional draft supplied by a caller; populates the editor without asking. */
  initialUtterance?: string | undefined;
  api?: Pick<AssistantApi, 'requestPlan' | 'applyPlan'>;
}

export interface AssistantPanelController {
  state: AssistantState;
  utterance: string;
  setUtterance(value: string): void;
  ask(): void;
  answerClarification(text: string): void;
  confirm(): void;
  cancel(): void;
  retry(): void;
  dismiss(): void;
  canConfirm: boolean;
}

/**
 * Wires the React-free assistant controller into component state.
 * Every asynchronous continuation goes through the controller's guarded
 * writer (mountedRef check), so no state is written after unmount (C21).
 */
export function useAssistantPanel({
  projectId,
  currentRevision,
  initialUtterance,
  api,
}: UseAssistantPanelOptions): AssistantPanelController {
  const [state, setState] = useState<AssistantState>({ phase: 'idle' });
  const [utterance, setUtterance] = useState(initialUtterance ?? '');
  const stateRef = useRef(state);
  const mountedRef = useRef(false);
  const projectIdRef = useRef(projectId);
  const revisionRef = useRef(currentRevision);
  const controllerRef = useRef<AssistantController | null>(null);

  if (controllerRef.current === null) {
    controllerRef.current = createAssistantController({
      api: api ?? createAssistantApi(),
      projectId: () => projectIdRef.current,
      currentRevision: () => revisionRef.current,
      read: () => stateRef.current,
      write: (next) => {
        if (!mountedRef.current) {
          return;
        }
        const value = next(stateRef.current);
        stateRef.current = value;
        setState(value);
      },
      isAlive: () => mountedRef.current,
    });
  }
  const controller = controllerRef.current;

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    setUtterance(initialUtterance ?? '');
  }, [initialUtterance]);

  useEffect(() => {
    projectIdRef.current = projectId;
    // R3-001 correction: switching projects must discard the previous
    // project's assistant state and in-flight tickets, or a stale preview
    // could be confirmed against the new project id.
    controller.reset();
  }, [controller, projectId]);

  // Stale detection: the page feeds the live revision (SSE operation.applied
  // refreshes the model, local dispatches update it too); a change against the
  // plan's base revision marks the preview STALE and blocks confirm.
  useEffect(() => {
    revisionRef.current = currentRevision;
    if (currentRevision !== null) {
      controller.noteRevision(currentRevision);
    }
  }, [controller, currentRevision]);

  return {
    state,
    utterance,
    setUtterance,
    ask: () => {
      controller.ask(utterance);
    },
    answerClarification: (text: string) => {
      controller.answerClarification(text);
    },
    confirm: () => {
      controller.confirm();
    },
    cancel: () => {
      controller.cancel();
    },
    retry: () => {
      controller.retry();
    },
    dismiss: () => {
      controller.dismiss();
    },
    canConfirm: canConfirm(state),
  };
}
