import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { AttributeResource, ClassifierResource, ModelResource, OperationEnvelope, OperationResource } from '../api/types';
import {
  ATTRIBUTE_STEREOTYPES,
  CLASSIFIER_STEREOTYPES,
  OPERATION_STEREOTYPES,
  applyOptimisticCommand,
  buildClassifierCreateEnvelope,
  buildClassifierSetStereotypeRequest,
  buildModelCommandEnvelope,
  type ModelCommandRequest,
} from './commands';
import { copySelection, pasteClipboard } from './clipboard';
import { default as PropertiesPanel, dispatchStereotypeSelection, stereotypeChange } from './PropertiesPanel';
import { UmlClassCard } from './UmlClassNode';
import { formatAttribute, formatOperation, formatStereotype } from './uml-format';
import type { CanvasSelection } from './selection';

const attribute = (over: Partial<AttributeResource> = {}): AttributeResource => ({
  id: 'at-1', name: 'id', kind: 'ATTRIBUTE', visibility: 'PRIVATE', multiplicity: 'EXACTLY_ONE',
  defaultValue: null, typeReferenceId: null, type: 'Long', ordinal: 0, ...over,
});

const operation = (over: Partial<OperationResource> = {}): OperationResource => ({
  id: 'op-1', name: 'find', visibility: 'PUBLIC', isAbstract: false, isStatic: false,
  returnTypeReferenceId: null, returnType: 'Order', ordinal: 0, parameters: [], ...over,
});

const classifier = (over: Partial<ClassifierResource> = {}): ClassifierResource => ({
  id: 'c-1', name: 'Order', kind: 'CLASS', isAbstract: false, namespaceId: null,
  attributes: [], operations: [], constraints: [], ...over,
});

const modelOf = (...classifiers: ClassifierResource[]): ModelResource => ({
  id: 'm-1', projectId: 'p-1', revision: 3, versionId: 'v-1',
  views: [{ id: 'view-1', name: 'Main', is_default: true, element_geometry: [], connector_path: [] }],
  classifiers, relationships: [],
});

function envelope(model: ModelResource, request: ModelCommandRequest): OperationEnvelope {
  return buildModelCommandEnvelope(model, request, () => 'new-id');
}

const renderPanel = (target: ClassifierResource, selection: CanvasSelection) => renderToStaticMarkup(
  createElement(PropertiesPanel, {
    model: modelOf(target),
    selection,
    onCommand: vi.fn(() => null),
    onDelete: () => undefined,
    onSelect: () => undefined,
  }),
);

describe('stereotype lists', () => {
  it('match the contract lists', () => {
    expect(CLASSIFIER_STEREOTYPES).toHaveLength(11);
    expect(CLASSIFIER_STEREOTYPES[0]).toBe('ENTITY');
    expect([...OPERATION_STEREOTYPES]).toEqual(['CREATE', 'DESTROY', 'QUERY']);
    expect([...ATTRIBUTE_STEREOTYPES]).toEqual(['ID', 'UNIQUE', 'READONLY']);
  });
});

describe('stereotype formatting', () => {
  it('prefixes a member with its lower-case guillemets before the visibility symbol', () => {
    expect(formatAttribute(attribute({ stereotype: 'ID' }))).toBe('«id» - id: Long');
    expect(formatOperation(operation({ stereotype: 'QUERY' }))).toBe('«query» + find(): Order');
    expect(formatStereotype('ENTITY')).toBe('«entity»');
  });

  it('leaves members without a stereotype byte-identical', () => {
    expect(formatAttribute(attribute())).toBe('- id: Long');
    expect(formatAttribute(attribute({ stereotype: null }))).toBe('- id: Long');
    expect(formatOperation(operation())).toBe('+ find(): Order');
    expect(formatOperation(operation({ stereotype: null }))).toBe('+ find(): Order');
  });

  it('shows the class stereotype above the name, and nothing without one', () => {
    const withStereotype = renderToStaticMarkup(createElement(UmlClassCard, { classifier: classifier({ stereotype: 'ENTITY' }) }));
    expect(withStereotype).toContain('data-testid="uml-class-stereotype"');
    expect(withStereotype).toContain('«entity»');
    expect(withStereotype.indexOf('«entity»')).toBeLessThan(withStereotype.indexOf('Order'));
    const plain = renderToStaticMarkup(createElement(UmlClassCard, { classifier: classifier() }));
    expect(plain).not.toContain('uml-class-stereotype');
    expect(plain).not.toContain('«');
  });

  it('keeps the interface label and does not add a stereotype to it', () => {
    const html = renderToStaticMarkup(createElement(UmlClassCard, { classifier: classifier({ kind: 'INTERFACE', stereotype: 'ENTITY' }) }));
    expect(html).toContain('«interfaz»');
    expect(html).not.toContain('«entity»');
  });
});

describe('stereotype commands', () => {
  it('builds the set request, with an explicit null to clear', () => {
    expect(buildClassifierSetStereotypeRequest('c-1', 'SERVICE')).toEqual({
      type: 'classifier.stereotype.set',
      targets: [{ scope: 'classifier', id: 'c-1' }],
      payload: { stereotype: 'SERVICE' },
    });
    const onCommand = vi.fn(() => null);
    dispatchStereotypeSelection(onCommand, 'c-1', '');
    expect(onCommand).toHaveBeenLastCalledWith(buildClassifierSetStereotypeRequest('c-1', null));
  });

  it('sends a member stereotype only when it changed, null when cleared', () => {
    expect(stereotypeChange(undefined, '')).toEqual({});
    expect(stereotypeChange(null, '')).toEqual({});
    expect(stereotypeChange('ID', 'ID')).toEqual({});
    expect(stereotypeChange(undefined, 'ID')).toEqual({ stereotype: 'ID' });
    expect(stereotypeChange('ID', '')).toEqual({ stereotype: null });
  });

  it('omits the stereotype from a classifier create unless one is chosen', () => {
    const model = modelOf();
    expect(buildClassifierCreateEnvelope(model, 'CLASS').payload).not.toHaveProperty('stereotype');
    expect(buildClassifierCreateEnvelope(model, 'CLASS', undefined, 'ENTITY').payload).toMatchObject({ stereotype: 'ENTITY' });
  });

  it('applies set, clear and create optimistically', () => {
    const model = modelOf(classifier());
    const set = applyOptimisticCommand(model, envelope(model, buildClassifierSetStereotypeRequest('c-1', 'ENTITY')));
    expect(set.classifiers[0]?.stereotype).toBe('ENTITY');
    const cleared = applyOptimisticCommand(set, envelope(set, buildClassifierSetStereotypeRequest('c-1', null)));
    expect(cleared.classifiers[0]?.stereotype).toBeNull();
    expect(model.classifiers[0]).not.toHaveProperty('stereotype');

    const created = applyOptimisticCommand(model, buildClassifierCreateEnvelope(model, 'CLASS', () => 'n-1', 'BOUNDARY'));
    expect(created.classifiers.find((item) => item.id === 'n-1')?.stereotype).toBe('BOUNDARY');
  });

  it('applies member add and update with the stereotype, absent key keeps it, null clears it', () => {
    const base = modelOf(classifier({ attributes: [attribute()], operations: [operation()] }));
    const target = [{ scope: 'classifier', id: 'c-1' }];
    const added = applyOptimisticCommand(base, envelope(base, {
      type: 'attribute.add', targets: target, payload: { name: 'code', type: { primitiveName: 'String' }, stereotype: 'UNIQUE' },
    }));
    expect(added.classifiers[0]?.attributes.find((item) => item.name === 'code')?.stereotype).toBe('UNIQUE');
    const opAdded = applyOptimisticCommand(base, envelope(base, {
      type: 'operation.add', targets: target, payload: { name: 'drop', stereotype: 'DESTROY' },
    }));
    expect(opAdded.classifiers[0]?.operations.find((item) => item.name === 'drop')?.stereotype).toBe('DESTROY');

    const attrTarget = [{ scope: 'attribute', id: 'at-1' }];
    const setAttr = applyOptimisticCommand(base, envelope(base, {
      type: 'attribute.update', targets: attrTarget, payload: { stereotype: 'ID' },
    }));
    expect(setAttr.classifiers[0]?.attributes[0]?.stereotype).toBe('ID');
    const keep = applyOptimisticCommand(setAttr, envelope(setAttr, {
      type: 'attribute.update', targets: attrTarget, payload: { visibility: 'PUBLIC' },
    }));
    expect(keep.classifiers[0]?.attributes[0]?.stereotype).toBe('ID');
    const clear = applyOptimisticCommand(setAttr, envelope(setAttr, {
      type: 'attribute.update', targets: attrTarget, payload: { stereotype: null },
    }));
    expect(clear.classifiers[0]?.attributes[0]?.stereotype).toBeNull();

    const opTarget = [{ scope: 'operation', id: 'op-1' }];
    const setOp = applyOptimisticCommand(base, envelope(base, {
      type: 'operation.update', targets: opTarget, payload: { stereotype: 'QUERY' },
    }));
    expect(setOp.classifiers[0]?.operations[0]?.stereotype).toBe('QUERY');
    const clearOp = applyOptimisticCommand(setOp, envelope(setOp, {
      type: 'operation.update', targets: opTarget, payload: { stereotype: null },
    }));
    expect(clearOp.classifiers[0]?.operations[0]?.stereotype).toBeNull();
  });
});

describe('stereotype select in the properties panel', () => {
  it('offers the fixed class list for a class and an abstract class, with a none option', () => {
    const html = renderPanel(classifier(), { kind: 'classifier', classifierId: 'c-1' });
    expect(html).toContain('id="classifier-stereotype"');
    expect(html).toContain('(ninguno)');
    expect(html).toContain('Entidad «entity»');
    expect(html).toContain('value="IMPLEMENTATION_CLASS"');
    expect(renderPanel(classifier({ kind: 'ABSTRACT_CLASS', isAbstract: true }), { kind: 'classifier', classifierId: 'c-1' }))
      .toContain('id="classifier-stereotype"');
  });

  it('does not offer a class stereotype for an interface, enumeration or package', () => {
    for (const kind of ['INTERFACE', 'ENUMERATION', 'PACKAGE']) {
      expect(renderPanel(classifier({ kind }), { kind: 'classifier', classifierId: 'c-1' })).not.toContain('classifier-stereotype');
    }
  });

  it('offers the attribute and operation lists on their forms', () => {
    const target = classifier({ attributes: [attribute()], operations: [operation()] });
    const attributeHtml = renderPanel(target, { kind: 'attribute', classifierId: 'c-1', elementId: 'at-1' });
    expect(attributeHtml).toContain('id="attribute-stereotype"');
    expect(attributeHtml).toContain('value="UNIQUE"');
    expect(attributeHtml).not.toContain('value="QUERY"');
    const operationHtml = renderPanel(target, { kind: 'operation', classifierId: 'c-1', elementId: 'op-1' });
    expect(operationHtml).toContain('id="operation-stereotype"');
    expect(operationHtml).toContain('value="DESTROY"');
    expect(operationHtml).not.toContain('value="UNIQUE"');
  });
});

describe('clipboard', () => {
  it('carries class and member stereotypes into the pasted copies', () => {
    const model = modelOf(classifier({
      stereotype: 'ENTITY',
      attributes: [attribute({ stereotype: 'ID' })],
      operations: [operation({ stereotype: 'QUERY' })],
    }));
    const clipboard = copySelection(model, ['c-1'])!;
    let current = model;
    const sent: OperationEnvelope[] = [];
    let counter = 0;
    pasteClipboard(clipboard, model, (request) => {
      counter += 1;
      const sentEnvelope = buildModelCommandEnvelope(current, request, () => `new-${counter}`);
      sent.push(sentEnvelope);
      current = applyOptimisticCommand(current, sentEnvelope);
      return sentEnvelope;
    }, 48);
    expect(sent.find((item) => item.type === 'classifier.create')?.payload).toMatchObject({ stereotype: 'ENTITY' });
    expect(sent.find((item) => item.type === 'attribute.add')?.payload).toMatchObject({ stereotype: 'ID' });
    expect(sent.find((item) => item.type === 'operation.add')?.payload).toMatchObject({ stereotype: 'QUERY' });
  });

  it('leaves the payloads without a stereotype key when the source has none', () => {
    const model = modelOf(classifier({ attributes: [attribute()], operations: [operation()] }));
    const clipboard = copySelection(model, ['c-1'])!;
    const sent: OperationEnvelope[] = [];
    pasteClipboard(clipboard, model, (request) => {
      const sentEnvelope = buildModelCommandEnvelope(model, request, () => `x-${sent.length}`);
      sent.push(sentEnvelope);
      return sentEnvelope;
    }, 48);
    for (const item of sent.filter((entry) => ['classifier.create', 'attribute.add', 'operation.add'].includes(entry.type))) {
      expect(item.payload).not.toHaveProperty('stereotype');
    }
  });
});
