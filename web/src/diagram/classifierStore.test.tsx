import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { ClassifierResource, ModelResource, OperationEnvelope } from '../api/types';
import { applyOptimisticCommand, buildClassifierSetStoreRequest } from './commands';
import { default as PropertiesPanel, dispatchStoreSelection } from './PropertiesPanel';
import { UmlClassCard } from './UmlClassNode';
import { describeStoreReason } from './StoreBadge';

const classifier = (over: Partial<ClassifierResource> = {}): ClassifierResource => ({
  id: '11111111-1111-4111-8111-111111111111',
  name: 'OrderLine',
  kind: 'CLASS',
  isAbstract: false,
  namespaceId: null,
  attributes: [],
  operations: [],
  constraints: [],
  ...over,
});

const modelOf = (...classifiers: ClassifierResource[]): ModelResource => ({
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  revision: 3,
  versionId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
  views: [],
  classifiers,
  relationships: [],
});

const envelopeOf = (store: unknown): OperationEnvelope => ({
  operationId: '22222222-2222-4222-8222-222222222222',
  type: 'classifier.setStore',
  author: { userId: '33333333-3333-4333-8333-333333333333', externalSubject: 'tester' },
  projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  modelId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  baseRevision: 3,
  targets: [{ scope: 'classifier', id: '11111111-1111-4111-8111-111111111111' }],
  preconditions: [],
  payload: { store },
  origin: 'MANUAL',
  causalParentOperationId: null,
  idempotencyKey: '44444444-4444-4444-8444-444444444444',
});

const renderPanel = (target: ClassifierResource, onCommand = vi.fn(() => null)) => renderToStaticMarkup(
  createElement(PropertiesPanel, {
    model: modelOf(target),
    selection: { kind: 'classifier', classifierId: target.id },
    onCommand,
    onDelete: () => undefined,
    onSelect: () => undefined,
  }),
);

describe('classifier persistence store', () => {
  it('translates the automatic relational store reason for display', () => {
    expect(describeStoreReason('No document signal, so it stays relational.'))
      .toBe('No hay señales de documentos, por lo que se mantiene relacional.');
  });

  it('sends the chosen store, or null to return to automatic, through classifier.setStore', () => {
    const onCommand = vi.fn(() => null);

    dispatchStoreSelection(onCommand, 'c-1', 'DOCUMENT');
    expect(onCommand).toHaveBeenLastCalledWith({
      type: 'classifier.setStore',
      targets: [{ scope: 'classifier', id: 'c-1' }],
      payload: { store: 'DOCUMENT' },
    });
    expect(onCommand).toHaveBeenLastCalledWith(buildClassifierSetStoreRequest('c-1', 'DOCUMENT'));

    dispatchStoreSelection(onCommand, 'c-1', '');
    expect(onCommand).toHaveBeenLastCalledWith(buildClassifierSetStoreRequest('c-1', null));
    expect(onCommand).toHaveBeenLastCalledWith({
      type: 'classifier.setStore',
      targets: [{ scope: 'classifier', id: 'c-1' }],
      payload: { store: null },
    });
  });

  it('projects a fixed store at once and drops it when the choice returns to automatic', () => {
    const automatic = modelOf(classifier({ store: 'DOCUMENT', storeReason: 'Part of a composition owned by Order.' }));

    const fixed = applyOptimisticCommand(automatic, envelopeOf('SQL'));
    expect(fixed.classifiers[0]).toMatchObject({ storeOverride: 'SQL', store: 'SQL', storeReason: 'Set manually.' });
    expect(automatic.classifiers[0]).not.toHaveProperty('storeOverride');

    const cleared = applyOptimisticCommand(fixed, envelopeOf(null));
    expect(cleared.classifiers[0]).not.toHaveProperty('storeOverride');
    expect(cleared.classifiers[0]).not.toHaveProperty('store');

    expect(applyOptimisticCommand(automatic, envelopeOf('GRAPH'))).toBe(automatic);
  });

  it('shows the effective store on the class with its reason, and marks a manual choice', () => {
    const automatic = renderToStaticMarkup(createElement(UmlClassCard, {
      classifier: classifier({ store: 'DOCUMENT', storeReason: 'Has list attributes, which a document stores natively.' }),
    }));
    expect(automatic).toContain('data-testid="uml-store-badge"');
    expect(automatic).toContain('data-store="DOCUMENT"');
    expect(automatic).toContain('data-manual="false"');
    expect(automatic).toContain('>Documento</span>');
    expect(automatic).toContain('title="Documento: Tiene atributos de lista, que un documento almacena de forma nativa."');

    const manual = renderToStaticMarkup(createElement(UmlClassCard, {
      classifier: classifier({ store: 'SQL', storeOverride: 'SQL', storeReason: 'Set manually.' }),
    }));
    expect(manual).toContain('data-store="SQL"');
    expect(manual).toContain('data-manual="true"');
    expect(manual).toContain('store-badge-pin');
  });

  it('draws no badge for a class without a store, such as one read from an older server', () => {
    expect(renderToStaticMarkup(createElement(UmlClassCard, { classifier: classifier() })))
      .not.toContain('uml-store-badge');
  });

  it('offers Automatic, SQL and Document with the reason, and selects the fixed choice', () => {
    const automatic = renderPanel(classifier({ store: 'DOCUMENT', storeReason: 'Part of a composition owned by Order.' }));
    expect(automatic).toContain('for="classifier-store">Almacenamiento</label>');
    expect(automatic).toContain('>Automático (Documento)</option>');
    expect(automatic).toContain('>SQL (relacional)</option>');
    expect(automatic).toContain('>Documento (MongoDB)</option>');
    expect(automatic).toContain('Forma parte de una composición propiedad de Order.');
    expect(automatic).not.toMatch(/<option value="(SQL|DOCUMENT)" selected/);

    const manual = renderPanel(classifier({ store: 'SQL', storeOverride: 'SQL', storeReason: 'Set manually.' }));
    expect(manual).toContain('>Automático</option>');
    expect(manual).toContain('Configurado manualmente.');
    expect(manual).toMatch(/<option value="SQL" selected/);
  });

  it('offers no storage choice for classes that are not persisted', () => {
    const iface = renderPanel(classifier({ kind: 'INTERFACE' }));
    expect(iface).not.toContain('classifier-store');
    const pkg = renderPanel(classifier({ kind: 'PACKAGE' }));
    expect(pkg).not.toContain('classifier-store');
  });
});
