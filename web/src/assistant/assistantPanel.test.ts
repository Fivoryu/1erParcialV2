import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiTransportError } from '../api/client';
import type {
  AssistantApi,
  AssistantApplyResource,
  AssistantPlanOperationResource,
  AssistantPlanResource,
} from '../api/assistant';
import {
  assistantErrorMessageFor,
  canConfirm,
  transitionAssistantState,
  type AssistantState,
} from './assistantState';
import type { SpeechRecognitionErrorReason } from './speechRecognition';
import { createAssistantController, type AssistantControllerDeps } from './assistantController';
import {
  AssistantPanelView,
  voiceErrorMessage,
  type AssistantVoiceProps,
} from './AssistantPanel';
import { createDiagramNames } from './assistantNames';
import { appendTranscript } from './speechTranscript';
import { useAssistantPanel } from './useAssistantPanel';

const projectId = '22222222-2222-4222-8222-222222222222';

const planOperation: AssistantPlanOperationResource = {
  type: 'classifier.create',
  targetId: null,
  args: { name: 'Cliente' },
};

const planResponse: AssistantPlanResource = {
  intent: 'PLAN',
  operations: [planOperation],
  question: null,
  explanation: null,
  diagnostics: [],
};

const applyResponse: AssistantApplyResource = {
  steps: [
    {
      index: 0,
      type: 'classifier.create',
      targetId: '33333333-3333-4333-8333-333333333333',
      status: 'ACCEPTED',
      operationId: '44444444-4444-4444-8444-444444444444',
      resultRevision: 8,
      diagnostics: [],
    },
  ],
  finalRevision: 8,
};

interface Harness {
  state(): AssistantState;
  writeCount(): number;
  setMounted(mounted: boolean): void;
  setRevision(revision: number | null): void;
  planCalls(): Array<{ utterance: string; baseRevision: number }>;
  applyCalls(): Array<{ baseRevision: number }>;
  resolvePlan(result: AssistantPlanResource): void;
  rejectPlan(error: unknown): void;
  resolveApply(result: AssistantApplyResource): void;
  rejectApply(error: unknown): void;
  controller: ReturnType<typeof createAssistantController>;
}

function createHarness(initialRevision: number | null = 7): Harness {
  let state: AssistantState = { phase: 'idle' };
  let mounted = true;
  let revision = initialRevision;
  let writeCount = 0;
  const planCalls: Array<{ utterance: string; baseRevision: number }> = [];
  const applyCalls: Array<{ baseRevision: number }> = [];

  let resolvePlan: (result: AssistantPlanResource) => void = () => undefined;
  let rejectPlan: (error: unknown) => void = () => undefined;
  let resolveApply: (result: AssistantApplyResource) => void = () => undefined;
  let rejectApply: (error: unknown) => void = () => undefined;

  const deps: AssistantControllerDeps = {
    api: {
      requestPlan(_projectId, utterance, baseRevision) {
        planCalls.push({ utterance, baseRevision });
        return new Promise<AssistantPlanResource>((resolve, reject) => {
          resolvePlan = resolve;
          rejectPlan = reject;
        });
      },
      applyPlan(_projectId, _operations, baseRevision) {
        applyCalls.push({ baseRevision });
        return new Promise<AssistantApplyResource>((resolve, reject) => {
          resolveApply = resolve;
          rejectApply = reject;
        });
      },
    },
    projectId: () => projectId,
    currentRevision: () => revision,
    read: () => state,
    write: (next) => {
      state = next(state);
      writeCount += 1;
    },
    isAlive: () => mounted,
  };

  return {
    state: () => state,
    writeCount: () => writeCount,
    setMounted: (value) => {
      mounted = value;
    },
    setRevision: (value) => {
      revision = value;
    },
    planCalls: () => planCalls,
    applyCalls: () => applyCalls,
    resolvePlan: (result) => resolvePlan(result),
    rejectPlan: (error) => rejectPlan(error),
    resolveApply: (result) => resolveApply(result),
    rejectApply: (error) => rejectApply(error),
    controller: createAssistantController(deps),
  };
}

const PRODUCT_ID = '11111111-1111-4111-8111-111111111111';
const SUPPLIER_ID = '22222222-2222-4222-8222-222222222222';
const ATTRIBUTE_ID = '33333333-3333-4333-8333-333333333333';
const RELATIONSHIP_ID = '44444444-4444-4444-8444-444444444444';
const MARKUP_NAMES = createDiagramNames({
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  revision: 7,
  versionId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  views: [],
  classifiers: [
    {
      id: PRODUCT_ID,
      name: 'Producto',
      kind: 'CLASS',
      isAbstract: false,
      namespaceId: null,
      attributes: [{
        id: ATTRIBUTE_ID,
        name: 'nombre',
        kind: 'ATTRIBUTE',
        visibility: 'PUBLIC',
        multiplicity: '1',
        defaultValue: null,
        typeReferenceId: null,
        type: 'String',
        ordinal: 0,
      }],
      operations: [],
      constraints: [],
    },
    {
      id: SUPPLIER_ID,
      name: 'Proveedor',
      kind: 'CLASS',
      isAbstract: false,
      namespaceId: null,
      attributes: [],
      operations: [],
      constraints: [],
    },
  ],
  relationships: [],
});
const MARKUP_PLAN: AssistantPlanResource = {
  intent: 'PLAN',
  operations: [
    { type: 'attribute.add', targetId: PRODUCT_ID, args: { name: 'nombre', type: 'String' } },
    {
      type: 'relationship.create',
      targetId: null,
      args: { source: PRODUCT_ID, target: SUPPLIER_ID, kind: 'ASSOCIATION' },
    },
  ],
  question: null,
  explanation: null,
  diagnostics: [],
};

function renderPanel(
  voice: AssistantVoiceProps | undefined,
  state: AssistantState = { phase: 'idle' },
): string {
  return renderToStaticMarkup(createElement(AssistantPanelView, {
    state,
    diagramNames: MARKUP_NAMES,
    utterance: '',
    canConfirm: false,
    onUtteranceChange: () => undefined,
    onAsk: () => undefined,
    onAnswerClarification: () => undefined,
    onConfirm: () => undefined,
    onCancel: () => undefined,
    onRetry: () => undefined,
    onDismiss: () => undefined,
    voice,
  }));
}

function makeVoice(overrides: Partial<AssistantVoiceProps> = {}): AssistantVoiceProps {
  return {
    supported: true,
    listening: false,
    interim: '',
    error: null,
    onToggle: () => undefined,
    ...overrides,
  };
}

const tick = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

beforeEach(() => {
  // Each test builds its own harness; this keeps vitest happy about isolation.
});

describe('assistant panel state machine (transitionAssistantState)', () => {
  it('moves from analyzing to preview when a PLAN with operations succeeds', () => {
    const analyzing = transitionAssistantState({ phase: 'idle' }, { type: 'ask_started', utterance: 'creá la clase Cliente' });
    expect(analyzing).toEqual({ phase: 'analyzing', utterance: 'creá la clase Cliente' });

    const preview = transitionAssistantState(analyzing, { type: 'plan_succeeded', plan: planResponse, baseRevision: 7 });
    expect(preview).toEqual({
      phase: 'preview',
      utterance: 'creá la clase Cliente',
      plan: planResponse,
      baseRevision: 7,
      stale: false,
    });
    expect(canConfirm(preview)).toBe(true);
  });

  it('shows a clarify question and re-asks with the answer as the new utterance', () => {
    const clarifyPlan: AssistantPlanResource = {
      intent: 'CLARIFY',
      operations: [],
      question: '¿A qué clase te referís?',
      explanation: null,
      diagnostics: [],
    };
    const analyzing = transitionAssistantState({ phase: 'idle' }, { type: 'ask_started', utterance: 'renombrala' });
    const clarify = transitionAssistantState(analyzing, { type: 'plan_succeeded', plan: clarifyPlan, baseRevision: 7 });
    expect(clarify.phase).toBe('clarify');
    if (clarify.phase === 'clarify') {
      expect(clarify.question).toBe('¿A qué clase te referís?');
    }
    const reAsked = transitionAssistantState(clarify, { type: 'ask_started', utterance: 'la clase Cliente' });
    expect(reAsked).toEqual({ phase: 'analyzing', utterance: 'la clase Cliente' });
  });

  it('marks the preview stale and blocks confirm when the revision moves', () => {
    const preview = transitionAssistantState(
      transitionAssistantState({ phase: 'idle' }, { type: 'ask_started', utterance: 'x' }),
      { type: 'plan_succeeded', plan: planResponse, baseRevision: 7 },
    );
    const stale = transitionAssistantState(preview, { type: 'revision_observed', revision: 9 });
    expect(stale.phase).toBe('preview');
    if (stale.phase === 'preview') {
      expect(stale.stale).toBe(true);
    }
    expect(canConfirm(stale)).toBe(false);

    // Same revision again does not change anything.
    expect(transitionAssistantState(stale, { type: 'revision_observed', revision: 9 })).toBe(stale);
  });

  it('records per-step apply results and ends in done', () => {
    const applying = transitionAssistantState(
      transitionAssistantState(
        transitionAssistantState({ phase: 'idle' }, { type: 'ask_started', utterance: 'x' }),
        { type: 'plan_succeeded', plan: planResponse, baseRevision: 7 },
      ),
      { type: 'apply_started' },
    );
    expect(applying.phase).toBe('applying');
    const done = transitionAssistantState(applying, { type: 'apply_succeeded', result: applyResponse });
    expect(done.phase).toBe('done');
    if (done.phase === 'done' && done.kind === 'applied') {
      expect(done.steps).toHaveLength(1);
      expect(done.steps[0]?.status).toBe('ACCEPTED');
      expect(done.plan).toEqual(planResponse);
      expect(done.finalRevision).toBe(8);
    } else {
      expect.unreachable('expected an applied done state');
    }
  });

  it('cancel from analyzing discards the in-flight request without mutating', () => {
    const analyzing = transitionAssistantState({ phase: 'idle' }, { type: 'ask_started', utterance: 'x' });
    const cancelled = transitionAssistantState(analyzing, { type: 'cancel_requested' });
    expect(cancelled).toEqual({ phase: 'cancelled' });
    // A late plan result after cancel must not resurrect a preview: the controller drops it by ticket.
  });
});

describe('assistant controller (React-free)', () => {
  it('plan success lands in preview and reports the request arguments', async () => {
    const harness = createHarness();
    harness.controller.ask('creá la clase Cliente');
    expect(harness.state().phase).toBe('analyzing');

    harness.resolvePlan(planResponse);
    await tick();

    expect(harness.state().phase).toBe('preview');
    expect(harness.planCalls()).toEqual([{ utterance: 'creá la clase Cliente', baseRevision: 7 }]);
  });

  it('answerClarification re-asks with the answer as the new utterance', async () => {
    const harness = createHarness();
    harness.controller.ask('renombrala');
    harness.resolvePlan({
      intent: 'CLARIFY',
      operations: [],
      question: '¿A qué clase te referís?',
      explanation: null,
      diagnostics: [],
    });
    await tick();
    expect(harness.state().phase).toBe('clarify');

    harness.controller.answerClarification('la clase Cliente');
    expect(harness.state().phase).toBe('analyzing');
    harness.resolvePlan(planResponse);
    await tick();

    expect(harness.planCalls().map((call) => call.utterance)).toEqual(['renombrala', 'la clase Cliente']);
    expect(harness.state().phase).toBe('preview');
  });

  it('confirm applies the current preview operations with the plan base revision and reports per-step results', async () => {
    const harness = createHarness();
    harness.controller.ask('creá la clase Cliente');
    harness.resolvePlan(planResponse);
    await tick();

    harness.controller.confirm();
    expect(harness.state().phase).toBe('applying');
    harness.resolveApply(applyResponse);
    await tick();

    expect(harness.applyCalls()).toEqual([{ baseRevision: 7 }]);
    const state = harness.state();
    expect(state.phase).toBe('done');
    if (state.phase === 'done' && state.kind === 'applied') {
      expect(state.steps.map((step) => step.status)).toEqual(['ACCEPTED']);
    } else {
      expect.unreachable('expected an applied done state');
    }
  });

  it('surfaces the 409 lease conflict message through the existing contract', async () => {
    const harness = createHarness();
    harness.controller.ask('creá la clase Cliente');
    harness.resolvePlan(planResponse);
    await tick();

    harness.controller.confirm();
    harness.rejectApply(new ApiTransportError(
      'EXPORT_BLOCKED_EDITING',
      'The model is locked by another editor.',
      [{ editorDisplayName: 'Ada', targetType: null, targetId: null, reclaimable: false, retryAfterSeconds: 30 }],
      null,
      409,
    ));
    await tick();

    const state = harness.state();
    expect(state.phase).toBe('error');
    if (state.phase === 'error') {
      expect(state.message).toContain('Ada');
      expect(state.retry).toEqual({ kind: 'confirm' });
    } else {
      expect.unreachable('expected a recoverable error state');
    }
  });

  it('a revision change after the plan blocks confirm until re-ask', async () => {
    const harness = createHarness();
    harness.controller.ask('creá la clase Cliente');
    harness.resolvePlan(planResponse);
    await tick();
    expect(canConfirm(harness.state())).toBe(true);

    harness.setRevision(9);
    harness.controller.noteRevision(9);
    expect(harness.state().phase).toBe('preview');
    expect(canConfirm(harness.state())).toBe(false);

    harness.controller.confirm();
    harness.resolveApply(applyResponse);
    await tick();
    // Confirm was blocked: no apply call was made and the state stayed stale-preview.
    expect(harness.applyCalls()).toHaveLength(0);
    expect(harness.state().phase).toBe('preview');
  });

  it('cancel mid-flight reconciles without duplicate mutation', async () => {
    const harness = createHarness();
    harness.controller.ask('creá la clase Cliente');
    expect(harness.state().phase).toBe('analyzing');

    harness.controller.cancel();
    expect(harness.state()).toEqual({ phase: 'cancelled' });
    const writesAtCancel = harness.writeCount();

    // The in-flight plan resolves late: it must be dropped, not applied nor re-run.
    harness.resolvePlan(planResponse);
    await tick();

    expect(harness.writeCount()).toBe(writesAtCancel);
    expect(harness.state()).toEqual({ phase: 'cancelled' });
    expect(harness.planCalls()).toHaveLength(1);
  });

  it('reset discards a preview and blocks its confirm (project switch protection, R3-001)', async () => {
    const harness = createHarness();
    harness.controller.ask('creá la clase Cliente');
    harness.resolvePlan(planResponse);
    await tick();
    expect(harness.state().phase).toBe('preview');

    // the hook now calls controller.reset() on every projectId change
    harness.controller.reset();
    expect(harness.state().phase).toBe('idle');

    // the discarded preview can no longer be applied
    harness.controller.confirm();
    await tick();
    expect(harness.applyCalls()).toHaveLength(0);
  });

  it('a late plan resolution after unmount writes no state and does not throw', async () => {
    const harness = createHarness();
    harness.controller.ask('creá la clase Cliente');
    await tick();
    const writesAfterAsk = harness.writeCount();

    harness.setMounted(false);
    expect(() => harness.resolvePlan(planResponse)).not.toThrow();
    await tick();

    expect(harness.writeCount()).toBe(writesAfterAsk);
    expect(harness.state().phase).toBe('analyzing');
  });
});

describe('assistant initial draft (markup)', () => {
  it('prefills initialUtterance without automatically asking or applying', () => {
    const prompt = 'Explicá los hallazgos del diagrama.';
    const requestPlan = vi.fn<AssistantApi['requestPlan']>().mockResolvedValue(planResponse);
    const applyPlan = vi.fn<AssistantApi['applyPlan']>().mockResolvedValue(applyResponse);

    function DraftHarness() {
      const assistant = useAssistantPanel({
        projectId,
        currentRevision: 7,
        initialUtterance: prompt,
        api: { requestPlan, applyPlan },
      });
      return createElement('div', { 'data-phase': assistant.state.phase },
        createElement('textarea', {
          'aria-label': 'Assistant draft',
          rows: 3,
          value: assistant.utterance,
          onChange: () => undefined,
        }));
    }

    const markup = renderToStaticMarkup(createElement(DraftHarness));

    expect(markup).toContain(`<textarea aria-label="Assistant draft" rows="3">${prompt}</textarea>`);
    expect(markup).toContain('data-phase="idle"');
    expect(requestPlan).not.toHaveBeenCalled();
    expect(applyPlan).not.toHaveBeenCalled();
  });
});

describe('assistant panel mic button (markup)', () => {
  it('uses a multiline utterance textarea and keeps clarification as a single-line input', () => {
    const mainPrompt = renderPanel(undefined);
    expect(mainPrompt).toContain('for="assistant-utterance">Consigna para el asistente</label>');
    expect(mainPrompt).toMatch(/<textarea[^>]*id="assistant-utterance"[^>]*rows="3"/);
    expect(mainPrompt).toContain('placeholder="Describí el cambio que querés hacer en el modelo…"');

    const clarification = renderPanel(undefined, {
      phase: 'clarify',
      utterance: '',
      question: '¿Qué elemento querés editar?',
    });
    expect(clarification).toContain('for="assistant-clarification">Respuesta a la aclaración</label>');
    expect(clarification).toContain('<input autoComplete="off" id="assistant-clarification"');
    expect(clarification).toContain('type="text"');
  });

  it('renders the mic button with the idle a11y state when speech is supported', () => {
    const markup = renderPanel(makeVoice());
    expect(markup).toContain('voice-mic-button');
    expect(markup).toContain('aria-pressed="false"');
    expect(markup).toContain('aria-label="Dictar por voz"');
  });

  it('hides the mic button entirely when speech recognition is not supported', () => {
    expect(renderPanel(makeVoice({ supported: false }))).not.toContain('voice-mic-button');
    expect(renderPanel(undefined)).not.toContain('voice-mic-button');
  });

  it('marks the listening state with aria-pressed, the stop label, and the active style', () => {
    const markup = renderPanel(makeVoice({ listening: true }));
    expect(markup).toContain('aria-pressed="true"');
    expect(markup).toContain('aria-label="Detener dictado"');
    expect(markup).toContain('voice-mic-listening');
  });

  it('shows the interim dictation as a live hint line under the input while listening', () => {
    const markup = renderPanel(makeVoice({ listening: true, interim: 'creá una clase' }));
    expect(markup).toContain('voice-hint');
    expect(markup).toContain('aria-live="polite"');
    expect(markup).toContain('creá una clase');
  });

  it('surfaces the human-readable dictation error as a tooltip and hint', () => {
    const denied = renderPanel(makeVoice({ error: 'not-allowed' as SpeechRecognitionErrorReason }));
    expect(denied).toContain('title="Permiso de micrófono denegado."');
    expect(denied).toContain('Permiso de micrófono denegado.');

    const network = renderPanel(makeVoice({ error: 'network' as SpeechRecognitionErrorReason }));
    expect(network).toContain('Error de red durante el dictado.');
  });

  it('reports no error copy while there is no dictation error', () => {
    expect(renderPanel(makeVoice())).not.toContain('voice-hint-error');
  });

  it('renders named attributes and relationships in both preview and applied steps without technical ids', () => {
    const preview = renderPanel(undefined, {
      phase: 'preview',
      utterance: 'crear atributo y relación',
      plan: MARKUP_PLAN,
      baseRevision: 7,
      stale: false,
    });
    const applied = renderPanel(undefined, {
      phase: 'done',
      kind: 'applied',
      plan: MARKUP_PLAN,
      steps: [
        {
          index: 0,
          type: 'attribute.add',
          targetId: PRODUCT_ID,
          status: 'ACCEPTED',
          operationId: '55555555-5555-4555-8555-555555555555',
          resultRevision: 8,
          diagnostics: [],
        },
        {
          index: 1,
          type: 'relationship.create',
          targetId: RELATIONSHIP_ID,
          status: 'ACCEPTED',
          operationId: '66666666-6666-4666-8666-666666666666',
          resultRevision: 8,
          diagnostics: [],
        },
        {
          index: 9,
          type: 'classifier.delete',
          targetId: '77777777-7777-4777-8777-777777777777',
          status: 'SKIPPED',
          operationId: null,
          resultRevision: null,
          diagnostics: [{
            code: 'PRECONDITION_FAILED',
            message: 'Error en destino 77777777-7777-4777-8777-777777777777: elemento nuevo.',
          }],
        },
      ],
      finalRevision: 8,
    });

    for (const markup of [preview, applied]) {
      expect(markup).toContain('Agregar atributo &quot;nombre: String&quot; a &quot;Producto&quot;');
      expect(markup).toContain('Crear relación de Asociación &quot;Producto&quot; → &quot;Proveedor&quot;');
      expect(markup).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
      expect(markup.toLowerCase()).not.toContain('elemento nuevo');
    }
    expect(applied).toContain('Eliminar clase seleccionada');
    expect(applied).toContain('destino el elemento seleccionado: el elemento seleccionado.');
  });
});

describe('appendTranscript', () => {
  it('appends the final transcript space-separated, trimmed on both sides', () => {
    expect(appendTranscript('creá la clase', 'Cliente'))
      .toBe('creá la clase Cliente');
    expect(appendTranscript('  creá la clase ', ' Cliente '))
      .toBe('creá la clase Cliente');
  });

  it('preserves what the user already typed when the transcript is empty', () => {
    expect(appendTranscript('creá la clase', '')).toBe('creá la clase');
    expect(appendTranscript('creá la clase', '   ')).toBe('creá la clase');
  });

  it('starts the utterance from the transcript when the input was empty', () => {
    expect(appendTranscript('', 'creá la clase Cliente')).toBe('creá la clase Cliente');
  });

  it('maps each persistent reason to a human-readable Spanish message', () => {
    expect(voiceErrorMessage('not-allowed')).toBe('Permiso de micrófono denegado.');
    expect(voiceErrorMessage('network')).toBe('Error de red durante el dictado.');
    expect(voiceErrorMessage('not-supported')).toContain('navegador');
  });
});

describe('assistant error message mapping', () => {
  it('maps the lease conflict carrier to a Spanish message with the editor name', () => {
    const message = assistantErrorMessageFor(new ApiTransportError(
      'EXPORT_BLOCKED_EDITING',
      'The model is locked by another editor.',
      [{ editorDisplayName: 'Ada', targetType: null, targetId: null, reclaimable: false, retryAfterSeconds: 30 }],
      null,
      409,
    ), 'fallback');
    expect(message).toContain('Ada');
    expect(message).toContain('30');
  });

  it('uses the transport error message verbatim for other codes and the fallback otherwise', () => {
    expect(assistantErrorMessageFor(
      new ApiTransportError('REVIEW_REQUIRED', 'La revisión está vencida.', [], null, 409),
      'fallback',
    )).toBe('La revisión está vencida.');
    expect(assistantErrorMessageFor('boom', 'fallback')).toBe('fallback');
  });
});
