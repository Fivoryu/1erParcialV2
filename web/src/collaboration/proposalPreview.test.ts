import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { ChatMessageResource, ChatProposalResource, ModelResource } from '../api/types';
import ChatPanel, { type ChatPanelProps } from './ChatPanel';
import { projectProposalPreview } from './proposalProjectionPreview';

vi.mock('react', async (original) => ({
  ...await original<typeof import('react')>(),
  useEffect: () => undefined,
  useRef: (current: unknown) => ({ current }),
  useState: (initial: unknown) => [initial, () => undefined],
}));
vi.mock('../assistant/useSpeechRecognition', () => ({
  useSpeechRecognition: () => ({ supported: false, listening: false, transcript: '', interim: '', error: null, start() {}, stop() {}, reset() {} }),
}));

const message: ChatMessageResource = {
  id: 'message', sessionId: 'session', kind: 'PROPOSAL', authorUserId: 'author',
  authorDisplayName: 'Autor', body: 'Sugerencia', createdAt: '2026-02-01T15:10:00Z',
};
const model: ModelResource = {
  id: 'model', projectId: 'project', revision: 4, versionId: 'version', views: [],
  classifiers: [
    { id: '00000000-0000-4000-8000-000000000001', name: 'Paciente', kind: 'CLASS', isAbstract: false, namespaceId: null, constraints: [], operations: [], attributes: [
      { id: '00000000-0000-4000-8000-000000000003', name: 'nombre', kind: 'ATTRIBUTE', visibility: 'PUBLIC', multiplicity: 'ONE', defaultValue: null, typeReferenceId: null, type: 'String', ordinal: 0 },
    ] },
    { id: '00000000-0000-4000-8000-000000000002', name: 'Doctor', kind: 'CLASS', isAbstract: false, namespaceId: null, constraints: [], operations: [], attributes: [] },
  ],
  relationships: [{ id: '00000000-0000-4000-8000-000000000004', kind: 'ASSOCIATION', name: 'atiende', constraints: [], ends: [
    { id: 'end1', classifierId: '00000000-0000-4000-8000-000000000001', role: null, multiplicity: 'ONE', navigability: 'UNSPECIFIED', aggregationKind: 'NONE', ordinal: 0 },
    { id: 'end2', classifierId: '00000000-0000-4000-8000-000000000002', role: null, multiplicity: 'ONE', navigability: 'UNSPECIFIED', aggregationKind: 'NONE', ordinal: 1 },
  ] }],
};
const proposal: ChatProposalResource = {
  id: 'proposal', messageId: message.id, sessionId: 'session', requestedByUserId: 'author',
  requestedByDisplayName: 'Autor', utterance: 'Cambiar diagrama', operations: [], baseRevision: 4,
  status: 'PENDING', decidedByUserId: null, decidedByDisplayName: null, decidedAt: null,
  resultSummary: null, createdAt: message.createdAt,
};

function render(operations: ChatProposalResource['operations'], options: { revision?: number; editor?: boolean } = {}) {
  const onAcceptProposal = vi.fn();
  const onRejectProposal = vi.fn();
  const onSend = vi.fn();
  const props: ChatPanelProps & { model: ModelResource } = {
    history: [message], userId: 'viewer', loading: false, error: null, draft: '', busy: false,
    onDraftChange() {}, onSend, onAskAi() {}, sessions: [], sessionsLoading: false,
    activeSessionId: 'session', sessionActionBusy: false, onSessionChange() {}, onCreateSession() {},
    aiStatus: null, contextMeter: null, contextMeterLoading: false, contextError: null,
    compactBusy: false, compactMessage: null, onCompact() {}, memories: [], memoriesLoading: false,
    memoryError: null, memoryMessage: null, extractBusy: false, onExtractMemories() {},
    canManageMemory: false, onForgetMemory() {}, onForgetAllMemories() {}, canDeleteHistory: false,
    onDeleteHistory() {}, historyMessage: null, attachmentUrls: {}, model,
    proposals: [{ ...proposal, operations, baseRevision: options.revision ?? 4 }],
    canDecideProposals: options.editor ?? true, onAcceptProposal, onRejectProposal,
  };
  const before = JSON.stringify(model);
  const html = renderToStaticMarkup(createElement(ChatPanel, props));
  return { html, before, after: JSON.stringify(model), onAcceptProposal, onRejectProposal, onSend };
}

const op = (type: string, targetId: string | null, args: Record<string, unknown> = {}) => ({ type, targetId, args });
const ids = { a: model.classifiers[0]!.id, b: model.classifiers[1]!.id, attr: model.classifiers[0]!.attributes[0]!.id, rel: model.relationships[0]!.id };

function expectItemState(html: string, item: string, state: string) {
  const tags = html.match(/<[^>]+>/g) ?? [];
  expect(tags.some((tag) => tag.includes(`data-preview-item="${item}"`) && tag.includes(`data-preview-state="${state}"`))).toBe(true);
}

describe('proposal projection prefix', () => {
  it('stops when a rename duplicates a classifier in the same namespace', () => {
    const result = projectProposalPreview(model, [op('classifier.rename', ids.a, { newName: 'Doctor' })]);
    expect(result.stoppedAt).toBe(0);
    expect(result.model.classifiers[0]?.name).toBe('Paciente');
  });

  it('falls back to name when newName is blank', () => {
    const result = projectProposalPreview(model, [op('classifier.rename', ids.a, { newName: '  ', name: 'Persona' })]);
    expect(result.stoppedAt).toBeNull();
    expect(result.model.classifiers[0]?.name).toBe('Persona');
  });
  it('keeps the live model unchanged and stops after an unsupported step', () => {
    const before = JSON.stringify(model);
    const result = projectProposalPreview(model, [
      op('classifier.rename', ids.a, { newName: 'Persona' }),
      op('unknown.operation', null),
      op('classifier.delete', ids.b),
    ]);
    expect(result.model.classifiers[0]?.name).toBe('Persona');
    expect(result.states[ids.a]).toBe('modified');
    expect(result.states[ids.b]).toBeUndefined();
    expect(result.stoppedAt).toBe(1);
    expect(JSON.stringify(model)).toBe(before);
  });

  it('skips a member that already exists and goes on, like the backend', () => {
    const result = projectProposalPreview(model, [
      op('attribute.add', ids.a, { name: 'nombre' }),
      op('classifier.rename', ids.b, { newName: 'Medico' }),
    ]);
    expect(result.stoppedAt).toBeNull();
    expect(result.model.classifiers[0]?.attributes).toHaveLength(1);
    expect(result.states[ids.b]).toBe('modified');
  });

  it('carries the numeric bounds of a RANGE attribute into the previewed model', () => {
    const result = projectProposalPreview(model, [
      op('attribute.add', ids.a, {
        name: 'telefonos', multiplicity: 'range', lowerBound: 2, upperBound: 5,
      }),
    ]);
    const added = result.model.classifiers[0]?.attributes.find((attribute) => attribute.name === 'telefonos');
    expect(added).toMatchObject({ multiplicity: 'RANGE', lowerBound: 2, upperBound: 5 });
  });

  it('ignores a bound that is not a whole number and leaves the other attributes unbounded', () => {
    const result = projectProposalPreview(model, [
      op('attribute.add', ids.a, { name: 'edad', multiplicity: 'range', lowerBound: 'dos', upperBound: 1.5 }),
    ]);
    const added = result.model.classifiers[0]?.attributes.find((attribute) => attribute.name === 'edad');
    expect(added).toMatchObject({ lowerBound: null, upperBound: null });
    expect(result.model.classifiers[0]?.attributes[0]?.lowerBound ?? null).toBeNull();
  });

  it('resolves classes created earlier in the plan by accent-insensitive name and stops on unknown ends', () => {
    const result = projectProposalPreview(model, [
      op('classifier.create', null, { name: 'Médico' }),
      op('attribute.add', null, { targetClassifierName: 'medico', name: 'matricula' }),
      op('relationship.create', null, { source: { classifierName: 'Paciente' }, target: { classifierName: 'Nadie' } }),
    ]);
    const created = result.model.classifiers.find((item) => item.name === 'Médico');
    expect(created?.attributes.map((attribute) => [attribute.name, attribute.type])).toEqual([['matricula', 'String']]);
    expect(result.states[created!.id]).toBe('added');
    expect(result.stoppedAt).toBe(2);
    expect(result.model.relationships).toHaveLength(1);
  });

  it('keeps deleted classes and attached relationships as ghosts, not later targets', () => {
    const result = projectProposalPreview(model, [
      op('classifier.delete', ids.a),
      op('classifier.rename', ids.a, { newName: 'No aplicar' }),
    ]);
    expect(result.model.classifiers[0]?.name).toBe('Paciente');
    expect(result.states[ids.a]).toBe('removed');
    expect(result.states[ids.rel]).toBe('removed');
    expect(result.stoppedAt).toBe(1);
  });
});

describe('visual proposal preview', () => {
  it('shows added class, member and relationship in green with non-color labels', () => {
    const { html } = render([
      op('classifier.create', null, { name: 'Turno' }),
      op('attribute.add', null, { targetClassifierName: 'Turno', name: 'fecha' }),
      op('relationship.create', null, { name: 'reserva', source: { classifierName: 'Paciente' }, target: { classifierName: 'Turno' } }),
    ]);
    expect(html).toMatch(/(?:Añadid|Agregad)/i);
    expect(html).toContain('Turno');
    expect(html).toContain('fecha');
    expectItemState(html, 'Turno', 'added');
    expectItemState(html, 'fecha', 'added');
    expectItemState(html, 'reserva', 'added');
    expect(html).toMatch(/react-flow|uml-class/);
  });

  it('keeps removed classes, members and relationships visible as red ghosts', () => {
    const { html } = render([
      op('attribute.remove', ids.attr), op('relationship.delete', ids.rel), op('classifier.delete', ids.b),
    ]);
    expect(html).toMatch(/Eliminad/i);
    expect(html).toContain('nombre');
    expect(html).toContain('atiende');
    expect(html).toContain('Doctor');
    expectItemState(html, 'nombre', 'removed');
    expectItemState(html, 'atiende', 'removed');
    expectItemState(html, 'Doctor', 'removed');
  });

  it('shows modified class, member and relationship in yellow', () => {
    const { html } = render([
      op('classifier.rename', ids.a, { newName: 'Persona' }),
      op('attribute.update', ids.attr, { name: 'apellido' }),
      op('relationship.update', ids.rel, { name: 'consulta' }),
    ]);
    expect(html).toMatch(/Modificad/i);
    expect(html).toContain('Persona');
    expect(html).toContain('apellido');
    expect(html).toContain('consulta');
    expectItemState(html, 'Persona', 'modified');
    expectItemState(html, 'apellido', 'modified');
    expectItemState(html, 'consulta', 'modified');
  });

  it('warns about stale and unsupported operations without pretending they were applied', () => {
    const { html } = render([op('unknown.operation', null)], { revision: 3 });
    expect(html).toMatch(/revisi[oó]n/i);
    expect(html).toMatch(/(?:parcial|no disponible)/i);
    expect(html).toMatch(/tentativ/i);
  });

  it('does not mutate or dispatch, and preserves editor and viewer decisions', () => {
    const result = render([op('classifier.rename', ids.a, { newName: 'Persona' })]);
    expect(result.after).toBe(result.before);
    expect(result.onAcceptProposal).not.toHaveBeenCalled();
    expect(result.onRejectProposal).not.toHaveBeenCalled();
    expect(result.onSend).not.toHaveBeenCalled();
    expect(result.html).toContain('Aceptar y aplicar');
    expect(result.html).toContain('Rechazar');
    const viewer = render([], { editor: false });
    expect(viewer.html).not.toContain('Aceptar y aplicar');
    expect(viewer.html).toContain('Solo una persona editora');
  });
});
