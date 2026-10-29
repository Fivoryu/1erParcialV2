// @ts-expect-error Node built-ins are available in Vitest, but this browser project omits Node type declarations.
import { readFileSync } from 'node:fs';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ClassifierResource, ModelResource } from '../api/types';
import { UmlClassCard } from './UmlClassNode';
import PropertiesPanel from './PropertiesPanel';
import { formatMultiplicity } from './uml-format';
import type { TouchedBy } from './touch-highlight';
import {
  isNodeMeasured,
  unplacedClassifierIds,
} from './UmlCanvas';

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
  versionId: '12121212-1212-4212-8212-121212121212',
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
    constraints: [],
  }],
};

describe('unplaced classifier detection', () => {
  const withoutGeometry = { ...classifier, id: '1a1a1a1a-1a1a-4a1a-8a1a-1a1a1a1a1a1a', name: 'Fresh' };
  const withoutGeometryToo = { ...classifier, id: '2b2b2b2b-2b2b-4b2b-8b2b-2b2b2b2b2b2b', name: 'Later' };

  it('never lists a classifier that has saved geometry in the default view', () => {
    const listed = unplacedClassifierIds(modelFixture, []);

    expect(listed).not.toContain(classifier.id);
  });

  it('skips a classifier without geometry that already has a node on screen', () => {
    const listed = unplacedClassifierIds(modelFixture, [{ id: interfaceClassifier.id }]);

    expect(listed).toEqual([]);
  });

  it('lists a classifier without geometry and without a node on screen', () => {
    expect(unplacedClassifierIds(modelFixture, [])).toEqual([interfaceClassifier.id]);
  });

  it('lists only the unplaced classifiers of a mixed model, in model order', () => {
    const mixed = {
      ...modelFixture,
      classifiers: [classifier, withoutGeometry, interfaceClassifier, withoutGeometryToo],
    };

    expect(unplacedClassifierIds(mixed, [{ id: interfaceClassifier.id }]))
      .toEqual([withoutGeometry.id, withoutGeometryToo.id]);
  });

  it('returns an empty list for an empty model', () => {
    expect(unplacedClassifierIds({ ...modelFixture, classifiers: [] }, [])).toEqual([]);
  });

  it('treats a classifier without a previous node as unplaced when the model has no default view', () => {
    // geometryFor depends on defaultView(model), so a viewless model can never show saved geometry:
    // every classifier is certain to land on the index-based fallback grid. Listing it (fitting) is the
    // safe choice, because a wrong fit only zooms out, while a missed fit hides the new class again.
    const viewless = { ...modelFixture, views: [] };

    expect(unplacedClassifierIds(viewless, [])).toEqual([classifier.id, interfaceClassifier.id]);
  });
});

describe('isNodeMeasured gate for the deferred fitView', () => {
  // React Flow v12 only includes nodes with measured dimensions in fitView bounds
  // (getFitViewNodes checks n.measured.width && n.measured.height). The top-level
  // width/height that buildFlowNodes sets from saved geometry or defaults do not
  // count, so the gate must reject them.
  it('rejects a node that does not exist yet', () => {
    expect(isNodeMeasured(undefined)).toBe(false);
  });

  it('rejects a node that only has declared dimensions and no measurement', () => {
    // `measured: undefined` satisfies tsc's weak-type check; at runtime this is exactly
    // what an unmeasured node looks like (no measurement reported yet).
    const declaredOnly = { id: 'fresh-node', width: 260, height: 180, measured: undefined };

    expect(isNodeMeasured(declaredOnly)).toBe(false);
    expect(isNodeMeasured({ measured: {} })).toBe(false);
    expect(isNodeMeasured({ measured: { width: 260 } })).toBe(false);
  });

  it('accepts a node with a non-zero measured width and height', () => {
    expect(isNodeMeasured({ measured: { width: 260, height: 180 } })).toBe(true);
  });

  it('rejects zero measured dimensions', () => {
    expect(isNodeMeasured({ measured: { width: 0, height: 0 } })).toBe(false);
  });
});

function renderClassifier(value: ClassifierResource, touchedBy?: TouchedBy[]): string {
  return renderToStaticMarkup(createElement(UmlClassCard, {
    classifier: value,
    ...(touchedBy ? { touchedBy } : {}),
  }));
}

function touchChipMarkup(toucher: TouchedBy): string {
  return `<span class="uml-touch-chip"><span aria-hidden="true" class="uml-touch-chip-swatch" style="background-color:${toucher.color}"></span><span class="uml-touch-chip-name">${toucher.displayName}</span></span>`;
}

describe('UML class node presentation', () => {
  it('renders UML names, markers, visibility, types, defaults, and constraints without technical identifiers', () => {
    const markup = renderClassifier(modelFixture.classifiers[0]!);
    const interfaceMarkup = renderClassifier(modelFixture.classifiers[1]!);

    expect(markup).toContain('Account');
    expect(markup).toContain('<i><span aria-label="Editar nombre de la clase" class="uml-editable-label" title="Haz doble clic para editar">Account</span></i>');
    expect(markup).toContain('- email: String = &#x27;guest@example.test&#x27;');
    expect(markup).not.toContain('String [1]');
    expect(markup).not.toContain('[1]');
    expect(markup).toContain('+ validate(): boolean');
    expect(markup).toContain('{email is not empty}');
    expect(interfaceMarkup).toContain('«interfaz»');
    expect(interfaceMarkup).toContain('Readable');

    for (const rendered of [markup, interfaceMarkup]) {
      expect(rendered).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
      for (const rawIdentifier of ['classifier_id', 'EXACTLY_ONE', 'ZERO_OR_MANY', 'NOT_NAVIGABLE', 'ABSTRACT_CLASS']) {
        expect(rendered).not.toContain(rawIdentifier);
      }
    }
  });

  it('shows one toucher name beside that toucher\'s own colour', () => {
    const toucher: TouchedBy = {
      userId: 'user-ada',
      displayName: 'Ada',
      color: '#c7503a',
    };
    const markup = renderClassifier(classifier, [toucher]);

    expect(markup).toContain(touchChipMarkup(toucher));
    expect(markup).not.toContain(toucher.userId);
  });

  it('shows both touchers with each name paired to that toucher\'s own colour', () => {
    const first: TouchedBy = { userId: 'user-ada', displayName: 'Ada', color: '#c7503a' };
    const second: TouchedBy = { userId: 'user-lin', displayName: 'Lin', color: '#347aa1' };
    const markup = renderClassifier(classifier, [first, second]);
    const firstChip = touchChipMarkup(first);
    const secondChip = touchChipMarkup(second);

    expect(markup).toContain(firstChip);
    expect(markup).toContain(secondChip);
    expect(markup.indexOf(firstChip)).toBeLessThan(markup.indexOf(secondChip));
    for (const toucher of [first, second]) {
      expect(markup).not.toContain(toucher.userId);
    }
  });

  it('renders no touch chip when nobody is touching the card', () => {
    const markupWithoutHighlights = renderClassifier(classifier);
    const markupWithEmptyHighlights = renderClassifier(classifier, []);

    expect(markupWithoutHighlights).not.toContain('uml-touch-chip');
    expect(markupWithEmptyHighlights).not.toContain('uml-touch-chip');
  });

  it('shows a symbol only for non-default constraint severity without spelling the enum value', () => {
    const warningMarkup = renderClassifier({
      ...classifier,
      constraints: [{ ...classifier.constraints[0]!, severity: 'WARNING' }],
    });
    const informationMarkup = renderClassifier({
      ...classifier,
      constraints: [{ ...classifier.constraints[0]!, severity: 'INFO' }],
    });
    const errorMarkup = renderClassifier({
      ...classifier,
      constraints: [{ ...classifier.constraints[0]!, severity: 'ERROR' }],
    });

    expect(warningMarkup).toContain('⚠');
    expect(warningMarkup).not.toContain('WARNING');
    expect(informationMarkup).toContain('ⓘ');
    expect(informationMarkup).not.toContain('INFO');
    expect(errorMarkup).toContain('{email is not empty}');
    expect(errorMarkup).not.toContain('uml-constraint-severity');
  });

  it('wraps a long member line instead of clipping its data', () => {
    const longType = 'VeryLongDomainTypeNameWithSeveralPartsThatMustRemainReadable';
    const markup = renderClassifier({
      ...classifier,
      attributes: [{ ...classifier.attributes[0]!, type: longType }],
    });
    const stylesheet = readFileSync(new URL('../styles.css', import.meta.url), 'utf8');
    const memberTextRule = stylesheet.match(/\.uml-member-text\s*\{([^}]*)\}/)?.[1] ?? '';
    const classCardRule = stylesheet.match(/\.uml-class-card\s*\{([^}]*)\}/)?.[1] ?? '';

    expect(markup).toContain(longType);
    expect(memberTextRule).toMatch(/overflow-wrap:\s*anywhere\s*;/);
    expect(memberTextRule).toMatch(/white-space:\s*normal\s*;/);
    expect(memberTextRule).not.toMatch(/overflow:\s*hidden|text-overflow:\s*ellipsis/);
    expect(classCardRule).toMatch(/overflow:\s*auto\s*;/);
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

  it('lists the class attributes and operations in sections that can be hidden', () => {
    const selected = modelFixture.classifiers[0]!;
    const markup = renderToStaticMarkup(createElement(PropertiesPanel, {
      model: modelFixture,
      selection: { kind: 'classifier', classifierId: selected.id },
      onCommand: () => null,
      onDelete: () => undefined,
      onSelect: () => undefined,
    }));

    expect(markup.match(/<details class="property-section member-section" open="">/g)).toHaveLength(2);
    expect(markup).toContain('<strong>Atributos</strong><span class="member-count">' + selected.attributes.length + '</span>');
    expect(markup).toContain('<strong>Operaciones</strong><span class="member-count">' + selected.operations.length + '</span>');
    for (const attribute of selected.attributes) {
      expect(markup).toContain(`<span>${attribute.name}</span>`);
    }
    expect(markup).toContain('Agregar atributo');
    expect(markup).toContain('Agregar operación');
  });

  it('offers editable expression and severity controls for a classifier constraint', () => {
    const selected = modelFixture.classifiers[0]!;
    const markup = renderToStaticMarkup(createElement(PropertiesPanel, {
      model: modelFixture,
      selection: { kind: 'classifier', classifierId: selected.id },
      onCommand: () => null,
      onDelete: () => undefined,
      onSelect: () => undefined,
    }));

    expect(markup).toContain('Expresión');
    expect(markup).toContain('Gravedad');
    expect(markup).toContain('Guardar restricción');
    expect(markup).toContain('Advertencia');
    expect(markup).toContain('Información');
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

    expect(markup).toContain('Público (+)');
    expect(markup).toContain('Privado (-)');
    expect(markup).toContain('0..1');
    expect(markup).not.toContain('Protegido');
    expect(markup).not.toContain('Paquete');
    expect(markup).not.toContain('RANGE');
    expect(markup).not.toContain('Atributo estático');
    expect(markup).not.toContain('Atributo abstracto');
  });
});

const enumeration: ClassifierResource = {
  id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
  name: 'Turno',
  kind: 'ENUMERATION',
  isAbstract: false,
  namespaceId: null,
  attributes: [
    {
      id: 'eeeeeeee-0000-4000-8000-000000000001',
      name: 'MANANA',
      kind: 'LITERAL',
      visibility: 'PUBLIC',
      multiplicity: 'EXACTLY_ONE',
      defaultValue: null,
      typeReferenceId: null,
      type: null,
      ordinal: 0,
    },
  ],
  operations: [],
  constraints: [],
};

describe('UML enumeration', () => {
  const model: ModelResource = { ...modelFixture, classifiers: [...modelFixture.classifiers, enumeration] };

  it('renders the enumeration stereotype and its literals without type or visibility', () => {
    const markup = renderClassifier(enumeration);

    expect(markup).toContain('«enumeración»');
    expect(markup).toContain('MANANA');
    expect(markup).not.toContain('+ MANANA');
  });

  it('adds untyped literals instead of attributes and operations', () => {
    const sent: unknown[] = [];
    const markup = renderToStaticMarkup(createElement(PropertiesPanel, {
      model,
      selection: { kind: 'classifier', classifierId: enumeration.id },
      onCommand: (request) => { sent.push(request); return null; },
      onDelete: () => undefined,
      onSelect: () => undefined,
    }));

    expect(markup).toContain('Enumeración');
    expect(markup).toContain('Agregar literal');
    expect(markup).not.toContain('Agregar atributo');
    expect(markup).not.toContain('Agregar operación');
  });

  it('edits a literal without a type, visibility or multiplicity', () => {
    const markup = renderToStaticMarkup(createElement(PropertiesPanel, {
      model,
      selection: { kind: 'attribute', classifierId: enumeration.id, elementId: enumeration.attributes[0]!.id },
      onCommand: () => null,
      onDelete: () => undefined,
      onSelect: () => undefined,
    }));

    expect(markup).toContain('Literal de enumeración');
    expect(markup).not.toContain('attribute-type');
    expect(markup).not.toContain('Visibilidad');
  });
});
