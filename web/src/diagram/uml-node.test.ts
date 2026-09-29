import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ClassifierResource, ModelResource } from '../api/types';
import { UmlClassCard } from './UmlClassNode';
import PropertiesPanel from './PropertiesPanel';
import { formatMultiplicity } from './uml-format';

const classifier: ClassifierResource = {
  id: '11111111-1111-4111-8111-111111111111',
  name: 'Account',
  kind: 'ABSTRACT_CLASS',
  isAbstract: true,
  namespaceId: null,
  attributes: [
    {
      id: '22222222-2222-4222-8222-222222222222',
      name: 'email',
      kind: 'ATTRIBUTE',
      visibility: 'PRIVATE',
      multiplicity: 'EXACTLY_ONE',
      defaultValue: "'guest@example.test'",
      typeReferenceId: '33333333-3333-4333-8333-333333333333',
      type: 'String',
      ordinal: 0,
    },
  ],
  operations: [
    {
      id: '44444444-4444-4444-8444-444444444444',
      name: 'validate',
      visibility: 'PUBLIC',
      isAbstract: false,
      isStatic: false,
      returnTypeReferenceId: '55555555-5555-4555-8555-555555555555',
      returnType: 'boolean',
      ordinal: 0,
      parameters: [],
    },
  ],
  constraints: [
    {
      id: '66666666-6666-4666-8666-666666666666',
      expression: 'email is not empty',
      severity: 'ERROR',
    },
  ],
};

const interfaceClassifier: ClassifierResource = {
  id: '77777777-7777-4777-8777-777777777777',
  name: 'Readable',
  kind: 'INTERFACE',
  isAbstract: false,
  namespaceId: null,
  attributes: [],
  operations: [],
  constraints: [],
};

const modelFixture: ModelResource = {
  id: '88888888-8888-4888-8888-888888888888',
  projectId: '99999999-9999-4999-8999-999999999999',
  revision: 7,
  views: [{
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    name: 'Class diagram',
    is_default: true,
    element_geometry: [{
      id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      targetType: 'CLASSIFIER',
      targetId: classifier.id,
      x: 120,
      y: 80,
      w: 260,
      h: 180,
      zOrder: 1,
    }],
    connector_path: [],
  }],
  classifiers: [classifier, interfaceClassifier],
  relationships: [{
    id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    kind: 'ASSOCIATION',
    name: 'accounts',
    ends: [{
      id: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
      classifierId: classifier.id,
      role: 'account',
      multiplicity: 'ZERO_OR_MANY',
      navigability: 'NOT_NAVIGABLE',
      aggregationKind: 'NONE',
      ordinal: 1,
    }],
  }],
};

function renderClassifier(value: ClassifierResource): string {
  return renderToStaticMarkup(createElement(UmlClassCard, { classifier: value }));
}

describe('UML class node presentation', () => {
  it('renders UML names, markers, visibility, types, defaults, and constraints without technical identifiers', () => {
    const markup = renderClassifier(modelFixture.classifiers[0]!);
    const interfaceMarkup = renderClassifier(modelFixture.classifiers[1]!);

    expect(markup).toContain('Account');
    expect(markup).toContain('<i><span class="uml-editable-label" title="Double-click to edit">Account</span></i>');
    expect(markup).toContain('- email: String = &#x27;guest@example.test&#x27;');
    expect(markup).not.toContain('String [1]');
    expect(markup).not.toContain('[1]');
    expect(markup).toContain('+ validate(): boolean');
    expect(markup).toContain('{email is not empty}');
    expect(interfaceMarkup).toContain('«interface»');
    expect(interfaceMarkup).toContain('Readable');

    expect(markup).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
    for (const rawIdentifier of ['classifier_id', 'EXACTLY_ONE', 'ZERO_OR_MANY', 'NOT_NAVIGABLE', 'ABSTRACT_CLASS']) {
      expect(markup).not.toContain(rawIdentifier);
    }
  });

  it('renders each supported multiplicity using UML notation', () => {
    expect(formatMultiplicity('EXACTLY_ONE')).toBe('1');
    expect(formatMultiplicity('ZERO_OR_ONE')).toBe('0..1');
    expect(formatMultiplicity('ONE_OR_MANY')).toBe('1..*');
    expect(formatMultiplicity('ZERO_OR_MANY')).toBe('0..*');

    const withMany = renderClassifier({
      ...classifier,
      attributes: [{ ...classifier.attributes[0]!, multiplicity: 'ZERO_OR_MANY' }],
    });
    expect(withMany).toContain('[0..*]');
    expect(withMany).not.toContain('ZERO_OR_MANY');
  });

  it('collapses empty attribute, operation, and constraint compartments', () => {
    const markup = renderClassifier({ ...classifier, attributes: [], operations: [], constraints: [] });

    expect(markup).toContain('Account');
    expect(markup).not.toContain('uml-compartment--attributes');
    expect(markup).not.toContain('uml-compartment--operations');
    expect(markup).not.toContain('uml-compartment--constraints');
  });

  it('orders attributes and operations by their model ordinals', () => {
    const markup = renderClassifier({
      ...classifier,
      attributes: [
        { ...classifier.attributes[0]!, id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', name: 'later', ordinal: 2 },
        { ...classifier.attributes[0]!, id: 'ffffffff-ffff-4fff-8fff-ffffffffffff', name: 'earlier', ordinal: 0 },
      ],
      operations: [
        { ...classifier.operations[0]!, id: '10101010-1010-4010-8010-101010101010', name: 'laterCall', ordinal: 3 },
        { ...classifier.operations[0]!, id: '20202020-2020-4020-8020-202020202020', name: 'firstCall', ordinal: 1 },
      ],
    });

    expect(markup.indexOf('earlier')).toBeLessThan(markup.indexOf('later'));
    expect(markup.indexOf('firstCall')).toBeLessThan(markup.indexOf('laterCall'));
  });

  it('does not offer attribute values the backend rejects', () => {
    const selected = modelFixture.classifiers[0]!;
    const markup = renderToStaticMarkup(createElement(PropertiesPanel, {
      model: modelFixture,
      selection: { kind: 'attribute', classifierId: selected.id, elementId: selected.attributes[0]!.id },
      onCommand: () => null,
      onDelete: () => undefined,
      onSelect: () => undefined,
    }));

    expect(markup).toContain('Public (+)');
    expect(markup).toContain('Private (-)');
    expect(markup).toContain('0..1');
    expect(markup).not.toContain('Protected');
    expect(markup).not.toContain('Package');
    expect(markup).not.toContain('RANGE');
    expect(markup).not.toContain('Static attribute');
    expect(markup).not.toContain('Abstract attribute');
  });
});
