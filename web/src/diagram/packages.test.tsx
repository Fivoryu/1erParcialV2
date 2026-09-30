import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { ClassifierResource, ModelResource } from '../api/types';
import { buildClassifierSetNamespaceRequest } from './commands';
import {
  availableNamespacePackages,
  default as PropertiesPanel,
  dispatchPackageSelection,
} from './PropertiesPanel';
import UmlCanvas from './UmlCanvas';
import { UmlClassCard } from './UmlClassNode';

const emptyClassifier = (id: string, name: string, namespaceId: string | null = null): ClassifierResource => ({
  id,
  name,
  kind: 'PACKAGE',
  isAbstract: false,
  namespaceId,
  attributes: [],
  operations: [],
  constraints: [],
});

const rootPackage = emptyClassifier('11111111-1111-4111-8111-111111111111', 'Billing');
const childPackage = emptyClassifier('22222222-2222-4222-8222-222222222222', 'Internal', rootPackage.id);
const nestedPackage = emptyClassifier('33333333-3333-4333-8333-333333333333', 'Persistence', childPackage.id);
const availablePackage = emptyClassifier('44444444-4444-4444-8444-444444444444', 'Shared');
const model: ModelResource = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  revision: 4,
  versionId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
  views: [],
  classifiers: [rootPackage, childPackage, nestedPackage, availablePackage],
  relationships: [],
};

function renderPackage(classifier: ClassifierResource): string {
  return renderToStaticMarkup(createElement(UmlClassCard, { classifier }));
}

describe('package support', () => {
  it('renders the folder-tab package glyph without compartments or technical identifiers', () => {
    const packageWithUnexpectedMembers: ClassifierResource = {
      ...rootPackage,
      attributes: [{
        id: '55555555-5555-4555-8555-555555555555',
        name: 'internalColumn',
        kind: 'ATTRIBUTE',
        visibility: 'PRIVATE',
        multiplicity: 'EXACTLY_ONE',
        defaultValue: null,
        typeReferenceId: null,
        type: 'String',
        ordinal: 0,
      }],
      operations: [{
        id: '66666666-6666-4666-8666-666666666666',
        name: 'internalOperation',
        visibility: 'PUBLIC',
        isAbstract: false,
        isStatic: false,
        returnTypeReferenceId: null,
        returnType: null,
        ordinal: 0,
        parameters: [],
      }],
      constraints: [{
        id: '77777777-7777-4777-8777-777777777777',
        expression: 'internal constraint',
        severity: 'ERROR',
      }],
    };
    const markup = renderPackage(packageWithUnexpectedMembers);

    expect(markup).toContain('class="uml-package-card"');
    expect(markup).toContain('class="uml-package-tab"');
    expect(markup).toContain('class="uml-package-name"');
    expect(markup).toContain('Billing');
    expect(markup).not.toContain('uml-class-card');
    expect(markup).not.toContain('uml-compartment');
    expect(markup).not.toContain('internalColumn');
    expect(markup).not.toContain('internalOperation');
    expect(markup).not.toContain('internal constraint');
    expect(markup).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
    for (const rawIdentifier of ['classifier_id', 'PACKAGE', 'EXACTLY_ONE']) {
      expect(markup).not.toContain(rawIdentifier);
    }
  });

  it('exposes the package palette action on the existing classifier creation path', () => {
    const markup = renderToStaticMarkup(createElement(UmlCanvas, {
      model,
      selection: null,
      onSelect: () => undefined,
      onCommand: () => undefined,
      onAddClassifier: () => undefined,
      onDelete: () => undefined,
    }));

    expect(markup).toContain('>+ Package</button>');
  });

  it('offers only packages outside the selected classifier namespace subtree', () => {
    expect(availableNamespacePackages(model, rootPackage.id)).toEqual([availablePackage]);
    const markup = renderToStaticMarkup(createElement(PropertiesPanel, {
      model,
      selection: { kind: 'classifier', classifierId: rootPackage.id },
      onCommand: vi.fn(() => null),
      onDelete: () => undefined,
      onSelect: () => undefined,
    }));

    expect(markup).toContain('for="classifier-package">Package</label>');
    expect(markup).toContain('>None</option>');
    expect(markup).toContain('>Shared</option>');
    expect(markup).not.toContain('>Billing</option>');
    expect(markup).not.toContain('>Internal</option>');
    expect(markup).not.toContain('>Persistence</option>');
  });

  it('sends the selected package id or null through classifier.setNamespace', () => {
    const onCommand = vi.fn(() => null);

    dispatchPackageSelection(onCommand, rootPackage.id, availablePackage.id);
    expect(onCommand).toHaveBeenLastCalledWith(buildClassifierSetNamespaceRequest(rootPackage.id, availablePackage.id));
    expect(onCommand).toHaveBeenLastCalledWith({
      type: 'classifier.setNamespace',
      targets: [{ scope: 'classifier', id: rootPackage.id }],
      payload: { namespaceId: availablePackage.id },
    });

    dispatchPackageSelection(onCommand, rootPackage.id, '');
    expect(onCommand).toHaveBeenLastCalledWith({
      type: 'classifier.setNamespace',
      targets: [{ scope: 'classifier', id: rootPackage.id }],
      payload: { namespaceId: null },
    });
  });
});
