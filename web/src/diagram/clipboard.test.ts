import { describe, expect, it } from 'vitest';
import type { ClassifierResource, ModelResource, OperationEnvelope, RelationshipResource } from '../api/types';
import { applyOptimisticCommand, buildModelCommandEnvelope, type ModelCommandRequest } from './commands';
import { copySelection, deleteClipboardClassifiers, pasteClipboard, relationshipsLostByCut } from './clipboard';

const VIEW_ID = 'view-1';

function classifier(id: string, name: string, extra: Partial<ClassifierResource> = {}): ClassifierResource {
  return {
    id,
    name,
    kind: 'CLASS',
    isAbstract: false,
    namespaceId: null,
    attributes: [],
    operations: [],
    constraints: [],
    ...extra,
  };
}

function relationship(id: string, sourceId: string, targetId: string, extra: Partial<RelationshipResource> = {}): RelationshipResource {
  return {
    id,
    kind: 'ASSOCIATION',
    name: null,
    ends: [
      { id: `${id}-e1`, classifierId: sourceId, role: 'owner', multiplicity: 'EXACTLY_ONE', navigability: 'UNSPECIFIED', aggregationKind: 'NONE', ordinal: 1 },
      { id: `${id}-e2`, classifierId: targetId, role: 'items', multiplicity: 'ZERO_OR_MORE', navigability: 'NAVIGABLE', aggregationKind: 'NONE', ordinal: 2 },
    ],
    constraints: [],
    ...extra,
  };
}

function geometry(targetId: string, x: number, y: number) {
  return { id: `g-${targetId}`, targetType: 'CLASSIFIER', targetId, x, y, w: 260, h: 120, zOrder: 2 };
}

function baseModel(): ModelResource {
  return {
    id: 'model-1',
    projectId: 'project-1',
    revision: 7,
    versionId: 'version-1',
    views: [{
      id: VIEW_ID,
      name: 'Main',
      is_default: true,
      element_geometry: [geometry('a', 0, 0), geometry('b', 400, 0), geometry('c', 800, 0)],
      connector_path: [{ id: 'p1', relationshipId: 'ab', ordinal: 0, x: 200, y: 50 }],
    }],
    classifiers: [
      classifier('a', 'Order', {
        attributes: [
          { id: 'a1', name: 'total', kind: 'ATTRIBUTE', visibility: 'PRIVATE', multiplicity: 'EXACTLY_ONE', defaultValue: '0', typeReferenceId: 't1', type: 'Double', ordinal: 0 },
          { id: 'a2', name: 'customer', kind: 'ATTRIBUTE', visibility: 'PRIVATE', multiplicity: 'EXACTLY_ONE', defaultValue: null, typeReferenceId: 't2', type: 'Customer', ordinal: 1 },
          { id: 'a3', name: 'line', kind: 'ATTRIBUTE', visibility: 'PRIVATE', multiplicity: 'EXACTLY_ONE', defaultValue: null, typeReferenceId: 't3', type: 'Line', ordinal: 2 },
        ],
        operations: [{
          id: 'o1', name: 'pay', visibility: 'PUBLIC', isAbstract: false, isStatic: true,
          returnTypeReferenceId: null, returnType: 'Line', ordinal: 0,
          parameters: [{ id: 'p1', name: 'amount', direction: 'IN', typeReferenceId: 't1', type: 'Double', ordinal: 0 }],
        }],
        constraints: [{ id: 'k1', expression: 'self.total >= 0', severity: 'ERROR' }],
        storeOverride: 'DOCUMENT',
      }),
      classifier('b', 'Line'),
      classifier('c', 'Customer'),
    ],
    relationships: [
      relationship('ab', 'a', 'b', { kind: 'COMPOSITION', name: 'has', constraints: [{ id: 'k2', expression: '{ordered}', severity: 'INFO' }] }),
      relationship('ac', 'a', 'c'),
    ],
    view_style: [{ viewId: VIEW_ID, targetId: 'ab', routing: 'STRAIGHT', cornerRadius: 4, strokeWidth: 3, dashed: true, routeAdjustment: null }],
  };
}

/** Dispatches like the editor does: builds the envelope (fresh element ids) and applies it optimistically. */
function recordingDispatch(start: ModelResource) {
  let model = start;
  let counter = 0;
  const sent: OperationEnvelope[] = [];
  const dispatch = (request: ModelCommandRequest): OperationEnvelope => {
    const envelope = buildModelCommandEnvelope(model, request, () => {
      counter += 1;
      return `new-${counter}`;
    });
    sent.push(envelope);
    model = applyOptimisticCommand(model, envelope);
    return envelope;
  };
  return { dispatch, sent, current: () => model };
}

function sentOfType(sent: OperationEnvelope[], type: string): OperationEnvelope[] {
  return sent.filter((envelope) => envelope.type === type);
}

describe('copySelection', () => {
  it('keeps only the relationships whose both ends are copied classes', () => {
    // A box selection also selects 'ac' because it touches 'a'; 'c' was not selected, so 'ac' stays out.
    const clipboard = copySelection(baseModel(), ['a', 'b']);

    expect(clipboard?.classifiers.map((entry) => entry.id)).toEqual(['a', 'b']);
    expect(clipboard?.relationships.map((entry) => entry.id)).toEqual(['ab']);
  });

  it('returns null when no class is selected', () => {
    expect(copySelection(baseModel(), [])).toBeNull();
  });
});

describe('pasteClipboard', () => {
  it('creates copies with fresh ids and unique names, offset on the canvas', () => {
    const model = baseModel();
    const clipboard = copySelection(model, ['a', 'b'])!;
    const { dispatch, sent, current } = recordingDispatch(model);

    const result = pasteClipboard(clipboard, model, dispatch, 48);

    const creates = sentOfType(sent, 'classifier.create');
    expect(creates.map((envelope) => envelope.payload.name)).toEqual(['OrderCopy', 'LineCopy']);
    expect(result.classifierIds).toEqual(creates.map((envelope) => envelope.payload.id));
    expect(result.classifierIds).not.toContain('a');
    const placed = sentOfType(sent, 'geometry.set').map((envelope) => envelope.payload);
    expect(placed).toEqual([
      expect.objectContaining({ targetType: 'CLASSIFIER', targetId: result.classifierIds[0], x: 48, y: 48, w: 260, h: 120, zOrder: 2 }),
      expect.objectContaining({ targetType: 'CLASSIFIER', targetId: result.classifierIds[1], x: 448, y: 48 }),
    ]);
    // The optimistic model already places the copies, so the canvas never parks them on the fallback grid.
    const view = current().views[0]!;
    expect(view.element_geometry.find((entry) => entry.targetId === result.classifierIds[0])).toMatchObject({ x: 48, y: 48 });
  });

  it('a second paste picks the next free name', () => {
    const model = baseModel();
    const clipboard = copySelection(model, ['b'])!;
    const { dispatch, sent, current } = recordingDispatch(model);

    pasteClipboard(clipboard, model, dispatch, 48);
    pasteClipboard(clipboard, current(), dispatch, 96);

    expect(sentOfType(sent, 'classifier.create').map((envelope) => envelope.payload.name)).toEqual(['LineCopy', 'LineCopy2']);
  });

  it('copies members, remapping types that point at copied classes', () => {
    const model = baseModel();
    const clipboard = copySelection(model, ['a', 'b'])!;
    const { dispatch, sent } = recordingDispatch(model);

    const result = pasteClipboard(clipboard, model, dispatch, 48);
    const [orderCopy, lineCopy] = result.classifierIds;

    const attributes = sentOfType(sent, 'attribute.add');
    expect(attributes.map((envelope) => envelope.targets[0]?.id)).toEqual([orderCopy, orderCopy, orderCopy]);
    expect(attributes.map((envelope) => envelope.payload)).toEqual([
      expect.objectContaining({ name: 'total', type: { primitiveName: 'Double' }, visibility: 'PRIVATE', defaultValue: '0', ordinal: 0 }),
      // Customer was not copied: the copy keeps pointing at the original class.
      expect.objectContaining({ name: 'customer', type: { classifierId: 'c' } }),
      expect.objectContaining({ name: 'line', type: { classifierId: lineCopy } }),
    ]);
    const [operation] = sentOfType(sent, 'operation.add');
    expect(operation?.payload).toEqual(expect.objectContaining({ name: 'pay', isStatic: true, returnType: { classifierId: lineCopy } }));
    const [parameter] = sentOfType(sent, 'parameter.add');
    expect(parameter?.targets).toEqual([{ scope: 'operation', id: operation?.payload.id }]);
    expect(parameter?.payload).toEqual(expect.objectContaining({ name: 'amount', type: { primitiveName: 'Double' }, direction: 'IN' }));
    const constraints = sentOfType(sent, 'constraint.add').map((envelope) => envelope.payload);
    expect(constraints).toContainEqual(expect.objectContaining({ ownerType: 'CLASSIFIER', ownerId: orderCopy, expression: 'self.total >= 0' }));
    expect(sentOfType(sent, 'classifier.setStore').map((envelope) => envelope.payload)).toEqual([{ store: 'DOCUMENT' }]);
  });

  it('recreates only the copied relationship between the copies, with its ends, route and style', () => {
    const model = baseModel();
    const clipboard = copySelection(model, ['a', 'b'])!;
    const { dispatch, sent } = recordingDispatch(model);

    const result = pasteClipboard(clipboard, model, dispatch, 48);
    const [orderCopy, lineCopy] = result.classifierIds;

    const relationships = sentOfType(sent, 'relationship.create');
    expect(relationships).toHaveLength(1);
    expect(relationships[0]?.targets).toEqual([
      { scope: 'classifier', id: orderCopy },
      { scope: 'classifier', id: lineCopy },
    ]);
    expect(relationships[0]?.payload).toEqual(expect.objectContaining({
      kind: 'COMPOSITION',
      name: 'has',
      source: { classifierId: orderCopy, role: 'owner', multiplicity: 'EXACTLY_ONE', navigability: 'UNSPECIFIED' },
      target: { classifierId: lineCopy, role: 'items', multiplicity: 'ZERO_OR_MORE', navigability: 'NAVIGABLE' },
      waypoints: [{ x: 248, y: 98 }],
    }));
    expect(result.relationshipIds).toEqual([relationships[0]?.payload.id]);
    expect(sentOfType(sent, 'constraint.add').map((envelope) => envelope.payload)).toContainEqual(
      expect.objectContaining({ ownerType: 'RELATIONSHIP', ownerId: relationships[0]?.payload.id, expression: '{ordered}' }),
    );
    expect(sentOfType(sent, 'style.set').map((envelope) => envelope.payload)).toEqual([
      expect.objectContaining({ viewId: VIEW_ID, targetId: relationships[0]?.payload.id, routing: 'STRAIGHT', strokeWidth: 3, dashed: true }),
    ]);
  });

  it('keeps the original name when it is free, as after a cut or in another project', () => {
    const model = baseModel();
    const clipboard = copySelection(model, ['b'])!;
    const elsewhere = { ...baseModel(), classifiers: [] };
    const { dispatch, sent } = recordingDispatch(elsewhere);

    pasteClipboard(clipboard, elsewhere, dispatch, 0);

    expect(sentOfType(sent, 'classifier.create').map((envelope) => envelope.payload.name)).toEqual(['Line']);
  });

  it('copying the pasted group again keeps the relationship between the copies', () => {
    // Found in the browser: the pasted relationships are not selected, and cutting the pasted
    // group warned that their own composition would be lost.
    const model = baseModel();
    const { dispatch, current } = recordingDispatch(model);
    const pasted = pasteClipboard(copySelection(model, ['a', 'b'])!, model, dispatch, 48);
    const withRelationship = {
      ...current(),
      relationships: [...current().relationships, relationship(pasted.relationshipIds[0]!, pasted.classifierIds[0]!, pasted.classifierIds[1]!)],
    };

    const again = copySelection(withRelationship, pasted.classifierIds)!;

    expect(again.relationships.map((entry) => entry.id)).toEqual(pasted.relationshipIds);
    expect(relationshipsLostByCut(withRelationship, again)).toBe(0);
  });

  it('pastes enumeration literals without a type', () => {
    const model = baseModel();
    model.classifiers.push(classifier('e', 'Status', {
      kind: 'ENUMERATION',
      attributes: [{ id: 'l1', name: 'OPEN', kind: 'LITERAL', visibility: 'PUBLIC', multiplicity: 'EXACTLY_ONE', defaultValue: null, typeReferenceId: null, type: null, ordinal: 0 }],
    }));
    const clipboard = copySelection(model, ['e'])!;
    const { dispatch, sent } = recordingDispatch(model);

    pasteClipboard(clipboard, model, dispatch, 48);

    expect(sentOfType(sent, 'attribute.add').map((envelope) => envelope.payload)).toEqual([
      expect.objectContaining({ name: 'OPEN', ordinal: 0 }),
    ]);
    expect(sentOfType(sent, 'attribute.add')[0]?.payload).not.toHaveProperty('type');
  });

  it('moves a copied class into the copy of its copied package', () => {
    const model = baseModel();
    model.classifiers.push(classifier('pkg', 'Sales', { kind: 'PACKAGE' }));
    model.classifiers.push(classifier('in', 'Invoice', { namespaceId: 'pkg' }));
    const clipboard = copySelection(model, ['pkg', 'in'])!;
    const { dispatch, sent } = recordingDispatch(model);

    const result = pasteClipboard(clipboard, model, dispatch, 48);

    expect(sentOfType(sent, 'classifier.setNamespace')).toEqual([
      expect.objectContaining({ targets: [{ scope: 'classifier', id: result.classifierIds[1] }], payload: { namespaceId: result.classifierIds[0] } }),
    ]);
  });
});

describe('cut', () => {
  it('deletes the copied classes with a cascade count that follows the earlier deletions', () => {
    const model = baseModel();
    const clipboard = copySelection(model, ['a', 'b'])!;
    const { dispatch, sent } = recordingDispatch(model);

    deleteClipboardClassifiers(clipboard, model, dispatch);

    const deletes = sentOfType(sent, 'classifier.delete');
    expect(deletes.map((envelope) => envelope.targets[0]?.id)).toEqual(['a', 'b']);
    // Order: itself + 3 attributes + 1 operation + 1 parameter + 1 constraint + (ab: 1+2+1) + (ac: 1+2) = 14.
    expect(deletes[0]?.preconditions).toEqual([{ kind: 'cascade_count', expect: 14, scope: null }]);
    // 'ab' already went with Order, so Line only counts itself.
    expect(deletes[1]?.preconditions).toEqual([{ kind: 'cascade_count', expect: 1, scope: null }]);
  });

  it('counts the relationships to classes outside the cut that the cut would also delete', () => {
    const model = baseModel();

    expect(relationshipsLostByCut(model, copySelection(model, ['a', 'b'])!)).toBe(1);
    expect(relationshipsLostByCut(model, copySelection(model, ['a', 'b', 'c'])!)).toBe(0);
  });
});
