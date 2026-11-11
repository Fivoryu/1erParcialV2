import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { ClassifierResource, ModelResource, NoteResource, OperationEnvelope, RelationshipResource } from '../api/types';
import {
  applyOptimisticCommand,
  buildGeometrySetRequest,
  buildModelCommandEnvelope,
  buildNoteCreateRequest,
  buildNoteDeleteRequest,
  buildNoteLinkRequest,
  buildNoteUnlinkRequest,
  buildNoteUpdateRequest,
  type ModelCommandRequest,
} from './commands';
import {
  default as PropertiesPanel,
  dispatchNoteBody,
  dispatchNoteLink,
  relationshipLabel,
} from './PropertiesPanel';
import ToolRail from './ToolRail';
import { UmlNoteCard } from './UmlNoteNode';
import { buildNoteGeometryRequests, buildNoteLinkEdges, buildNoteNodes } from './UmlCanvas';
import { noteSummary } from './uml-format';
import { projectProposalPreview } from '../collaboration/proposalProjectionPreview';

const classifier = (id: string, name: string): ClassifierResource => ({
  id, name, kind: 'CLASS', isAbstract: false, namespaceId: null, attributes: [], operations: [], constraints: [],
});

const relationship = (id: string, from: string, to: string): RelationshipResource => ({
  id,
  kind: 'ASSOCIATION',
  name: null,
  ends: [
    { id: `${id}-1`, ordinal: 1, classifierId: from, role: null, multiplicity: 'EXACTLY_ONE', navigability: 'NAVIGABLE' },
    { id: `${id}-2`, ordinal: 2, classifierId: to, role: null, multiplicity: 'EXACTLY_ONE', navigability: 'NAVIGABLE' },
  ],
  constraints: [],
} as unknown as RelationshipResource);

const note = (over: Partial<NoteResource> = {}): NoteResource => ({
  id: 'n-1', body: 'Solo para clientes registrados', annotatedElements: [], ...over,
});

const modelOf = (notes?: NoteResource[]): ModelResource => ({
  id: 'm-1', projectId: 'p-1', revision: 3, versionId: 'v-1',
  views: [{ id: 'view-1', name: 'Main', is_default: true, element_geometry: [], connector_path: [] }],
  classifiers: [classifier('c-1', 'Order'), classifier('c-2', 'Customer')],
  relationships: [relationship('r-1', 'c-1', 'c-2')],
  ...(notes ? { notes } : {}),
});

function envelope(model: ModelResource, request: ModelCommandRequest): OperationEnvelope {
  return buildModelCommandEnvelope(model, request, () => 'new-id');
}

describe('note commands', () => {
  it('builds create, update, delete, link and unlink with the contract payloads', () => {
    expect(buildNoteCreateRequest('Hola', ['c-1'])).toEqual({
      type: 'note.create', payload: { body: 'Hola', annotatedElementIds: ['c-1'] },
    });
    expect(buildNoteCreateRequest('Hola').payload).toEqual({ body: 'Hola', annotatedElementIds: [] });
    expect(buildNoteUpdateRequest('n-1', 'Otro')).toEqual({
      type: 'note.update', targets: [{ scope: 'note', id: 'n-1' }], payload: { body: 'Otro' },
    });
    expect(buildNoteDeleteRequest('n-1')).toEqual({
      type: 'note.delete', targets: [{ scope: 'note', id: 'n-1' }], payload: {},
    });
    expect(buildNoteLinkRequest('n-1', 'RELATIONSHIP', 'r-1')).toEqual({
      type: 'note.link', targets: [{ scope: 'note', id: 'n-1' }], payload: { targetType: 'RELATIONSHIP', targetId: 'r-1' },
    });
    expect(buildNoteUnlinkRequest('n-1', 'CLASSIFIER', 'c-1')).toEqual({
      type: 'note.unlink', targets: [{ scope: 'note', id: 'n-1' }], payload: { targetType: 'CLASSIFIER', targetId: 'c-1' },
    });
  });

  it('gives the created note a client-owned id in the payload', () => {
    const created = envelope(modelOf(), buildNoteCreateRequest('Hola', ['c-1']));
    expect(created.type).toBe('note.create');
    expect(created.payload.id).toBe('new-id');
  });

  it('sends the body only when it is not blank and changed', () => {
    const onCommand = vi.fn(() => null);
    expect(dispatchNoteBody(onCommand, note(), '   ')).toBe(false);
    expect(dispatchNoteBody(onCommand, note(), note().body)).toBe(false);
    expect(onCommand).not.toHaveBeenCalled();
    expect(dispatchNoteBody(onCommand, note(), 'Nuevo texto')).toBe(true);
    expect(onCommand).toHaveBeenCalledWith(buildNoteUpdateRequest('n-1', 'Nuevo texto'));
  });

  it('links the element chosen in the select and ignores an empty choice', () => {
    const onCommand = vi.fn(() => null);
    expect(dispatchNoteLink(onCommand, 'n-1', '')).toBe(false);
    expect(onCommand).not.toHaveBeenCalled();
    expect(dispatchNoteLink(onCommand, 'n-1', 'CLASSIFIER:c-2')).toBe(true);
    expect(onCommand).toHaveBeenLastCalledWith(buildNoteLinkRequest('n-1', 'CLASSIFIER', 'c-2'));
    expect(dispatchNoteLink(onCommand, 'n-1', 'RELATIONSHIP:r-1')).toBe(true);
    expect(onCommand).toHaveBeenLastCalledWith(buildNoteLinkRequest('n-1', 'RELATIONSHIP', 'r-1'));
  });

  it('accepts NOTE as a geometry target', () => {
    expect(buildGeometrySetRequest({
      targetType: 'NOTE', viewId: 'view-1', targetId: 'n-1', x: 1, y: 2, w: 3, h: 4, zOrder: 0,
    })).toEqual({
      type: 'geometry.set',
      payload: { targetType: 'NOTE', viewId: 'view-1', targetId: 'n-1', x: 1, y: 2, w: 3, h: 4, zOrder: 0 },
    });
  });
});

describe('note optimistic model', () => {
  it('creates a note with its links, dropping unknown elements and duplicates', () => {
    const model = modelOf();
    const request = buildNoteCreateRequest('Texto', ['c-1', 'r-1', 'c-1', 'ghost']);
    const next = applyOptimisticCommand(model, envelope(model, request));
    expect(next.notes).toEqual([{
      id: 'new-id',
      body: 'Texto',
      annotatedElements: [
        { targetType: 'CLASSIFIER', targetId: 'c-1' },
        { targetType: 'RELATIONSHIP', targetId: 'r-1' },
      ],
    }]);
  });

  it('updates the body, links without duplicating, unlinks and deletes', () => {
    const model = modelOf([note({ annotatedElements: [{ targetType: 'CLASSIFIER', targetId: 'c-1' }] })]);
    const updated = applyOptimisticCommand(model, envelope(model, buildNoteUpdateRequest('n-1', 'Editada')));
    expect(updated.notes?.[0]?.body).toBe('Editada');
    const linked = applyOptimisticCommand(updated, envelope(updated, buildNoteLinkRequest('n-1', 'RELATIONSHIP', 'r-1')));
    expect(linked.notes?.[0]?.annotatedElements).toEqual([
      { targetType: 'CLASSIFIER', targetId: 'c-1' },
      { targetType: 'RELATIONSHIP', targetId: 'r-1' },
    ]);
    const again = applyOptimisticCommand(linked, envelope(linked, buildNoteLinkRequest('n-1', 'RELATIONSHIP', 'r-1')));
    expect(again.notes?.[0]?.annotatedElements).toHaveLength(2);
    const unlinked = applyOptimisticCommand(again, envelope(again, buildNoteUnlinkRequest('n-1', 'CLASSIFIER', 'c-1')));
    expect(unlinked.notes?.[0]?.annotatedElements).toEqual([{ targetType: 'RELATIONSHIP', targetId: 'r-1' }]);
    const removed = applyOptimisticCommand(unlinked, envelope(unlinked, buildNoteDeleteRequest('n-1')));
    expect(removed.notes).toEqual([]);
  });

  it('removes the links of a deleted class and of its relationships, keeping the note', () => {
    const model = modelOf([note({
      annotatedElements: [
        { targetType: 'CLASSIFIER', targetId: 'c-1' },
        { targetType: 'RELATIONSHIP', targetId: 'r-1' },
        { targetType: 'CLASSIFIER', targetId: 'c-2' },
      ],
    })]);
    const next = applyOptimisticCommand(model, envelope(model, {
      type: 'classifier.delete', targets: [{ scope: 'classifier', id: 'c-1' }], payload: {},
    }));
    expect(next.notes).toEqual([{ ...note(), annotatedElements: [{ targetType: 'CLASSIFIER', targetId: 'c-2' }] }]);
  });

  it('keeps a model without notes untouched by class deletion and projects NOTE geometry', () => {
    const model = modelOf();
    const next = applyOptimisticCommand(model, envelope(model, {
      type: 'classifier.delete', targets: [{ scope: 'classifier', id: 'c-2' }], payload: {},
    }));
    expect('notes' in next).toBe(false);
    const placed = applyOptimisticCommand(model, envelope(model, buildGeometrySetRequest({
      targetType: 'NOTE', viewId: 'view-1', targetId: 'n-1', x: 10, y: 20, w: 200, h: 100, zOrder: 1,
    })));
    expect(placed.views[0]?.element_geometry).toEqual([
      { id: 'n-1', targetType: 'NOTE', targetId: 'n-1', x: 10, y: 20, w: 200, h: 100, zOrder: 1 },
    ]);
  });
});

describe('note rendering', () => {
  it('draws the body text inside the note card', () => {
    const html = renderToStaticMarkup(createElement(UmlNoteCard, { note: note({ body: 'Línea uno\nLínea dos' }) }));
    expect(html).toContain('uml-note-card');
    expect(html).toContain('Línea uno\nLínea dos');
    expect(html).toContain('aria-label="Nota: Línea uno"');
  });

  it('summarises long and empty bodies', () => {
    expect(noteSummary('   ')).toBe('(nota vacía)');
    expect(noteSummary('x'.repeat(60))).toHaveLength(40);
    expect(noteSummary('a\nb')).toBe('a');
  });

  it('builds one note node per note, falling back to a column right of the classes', () => {
    const nodes = buildNoteNodes(modelOf([note(), note({ id: 'n-2', body: 'Otra' })]), []);
    const notes = nodes.filter((node) => node.type === 'umlNote');
    expect(notes.map((node) => node.id)).toEqual(['n-1', 'n-2']);
    expect(notes[0]?.position).toEqual({ x: 1076, y: 56 });
    expect(notes[1]?.position).toEqual({ x: 1076, y: 224 });
    expect(modelOf().notes).toBeUndefined();
    expect(buildNoteNodes(modelOf(), [])).toEqual([]);
  });

  it('uses saved NOTE geometry for position and width', () => {
    const model = modelOf([note()]);
    model.views[0]!.element_geometry.push({
      id: 'g-1', targetType: 'NOTE', targetId: 'n-1', x: 300, y: 40, w: 250, h: 120, zOrder: 2,
    });
    const [node] = buildNoteNodes(model, []);
    expect(node?.position).toEqual({ x: 300, y: 40 });
    expect(node?.width).toBe(250);
    expect(node?.zIndex).toBe(2);
  });

  it('draws one dashed edge from the note to each annotated class', () => {
    const edges = buildNoteLinkEdges(modelOf([note({
      annotatedElements: [
        { targetType: 'CLASSIFIER', targetId: 'c-1' },
        { targetType: 'CLASSIFIER', targetId: 'c-2' },
      ],
    })]));
    expect(edges.map((edge) => [edge.source, edge.target])).toEqual([['n-1', 'c-1'], ['n-1', 'c-2']]);
    expect(edges.every((edge) => edge.style?.strokeDasharray === '6 4')).toBe(true);
    expect(edges[0]?.sourceHandle).toMatch(/^source-(top|right|bottom|left)$/);
    expect(edges[0]?.targetHandle).toMatch(/^target-(top|right|bottom|left)$/);
  });

  it('ends a link to a relationship on an anchor node and skips links to missing elements', () => {
    const model = modelOf([note({
      annotatedElements: [
        { targetType: 'RELATIONSHIP', targetId: 'r-1' },
        { targetType: 'CLASSIFIER', targetId: 'gone' },
      ],
    })]);
    const edges = buildNoteLinkEdges(model);
    expect(edges).toHaveLength(1);
    expect(edges[0]?.target).toBe('note-anchor:r-1');
    expect(edges[0]?.data).toEqual({ noteId: 'n-1', targetType: 'RELATIONSHIP', targetId: 'r-1' });
    const anchors = buildNoteNodes(model, []).filter((node) => node.type === 'umlNoteAnchor');
    expect(anchors.map((node) => node.id)).toEqual(['note-anchor:r-1']);
  });

  it('requests geometry once for a measured note without saved geometry', () => {
    const model = modelOf([note()]);
    const [node] = buildNoteNodes(model, []);
    expect(buildNoteGeometryRequests(model, [node!], new Set())).toEqual([]);
    const measured = { ...node!, measured: { width: 200, height: 96 } };
    expect(buildNoteGeometryRequests(model, [measured], new Set())).toEqual([{
      targetType: 'NOTE', viewId: 'view-1', targetId: 'n-1', x: 1076, y: 56, w: 200, h: 96, zOrder: 0,
    }]);
    expect(buildNoteGeometryRequests(model, [measured], new Set(['n-1']))).toEqual([]);
  });
});

describe('note properties panel', () => {
  const render = (selectedNote: NoteResource | null, selection = { kind: 'note', noteId: 'n-1' } as const) => renderToStaticMarkup(
    createElement(PropertiesPanel, {
      model: modelOf(selectedNote ? [selectedNote] : undefined),
      selection,
      onCommand: vi.fn(() => null),
      onDelete: () => undefined,
      onSelect: () => undefined,
    }),
  );

  it('edits the body, lists the linked elements and offers the others to link', () => {
    const html = render(note({ annotatedElements: [{ targetType: 'CLASSIFIER', targetId: 'c-1' }] }));
    expect(html).toContain('aria-label="Texto de la nota"');
    expect(html).toContain('Solo para clientes registrados');
    expect(html).toContain('Guardar nota');
    expect(html).toContain('Eliminar nota');
    expect(html).toContain('Clase: Order');
    expect(html).toContain('aria-label="Quitar vínculo con Order"');
    expect(html).toContain('aria-label="Vincular la nota a"');
    expect(html).toContain('value="CLASSIFIER:c-2"');
    expect(html).toContain('value="RELATIONSHIP:r-1"');
    expect(html).not.toContain('value="CLASSIFIER:c-1"');
  });

  it('says so when the note has no links and when it no longer exists', () => {
    expect(render(note())).toContain('La nota no está vinculada a ningún elemento.');
    expect(render(null)).toContain('Esta nota ya no está en el modelo.');
  });

  it('names a relationship by its name or its kind and ends', () => {
    const model = modelOf();
    expect(relationshipLabel(model, model.relationships[0]!)).toBe('ASSOCIATION (Order – Customer)');
    expect(relationshipLabel(model, { ...model.relationships[0]!, name: 'places' })).toBe('places (Order – Customer)');
  });
});

describe('note tool', () => {
  const rail = (onAddNote?: () => void) => renderToStaticMarkup(createElement(ToolRail, {
    relationKind: 'ASSOCIATION',
    relationActive: false,
    onSelectTool: () => undefined,
    onAddClassifier: () => undefined,
    ...(onAddNote ? { onAddNote } : {}),
    onToggleRelation: () => undefined,
    onRelationKindChange: () => undefined,
  }));

  it('shows the add-note tool only when the page wires it', () => {
    expect(rail(() => undefined)).toContain('title="Agregar una nota UML"');
    expect(rail()).not.toContain('Agregar una nota UML');
  });
});

describe('proposal preview with notes', () => {
  it('projects a model that has notes without touching them', () => {
    const model = modelOf([note({ annotatedElements: [{ targetType: 'CLASSIFIER', targetId: 'c-1' }] })]);
    const preview = projectProposalPreview(model, []);
    expect(preview.model.notes).toEqual(model.notes);
    expect(preview.model.notes).not.toBe(model.notes);
  });
});
