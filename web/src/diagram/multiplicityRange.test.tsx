import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { AttributeResource, ClassifierResource, ModelResource, RelationshipResource } from '../api/types';
import {
  applyOptimisticCommand,
  buildModelCommandEnvelope,
  buildRelationshipCreateRequest,
  buildRelationshipUpdateRequest,
} from './commands';
import { multiplicityUpdateRequest } from './contextMenuBehavior';
import PropertiesPanel from './PropertiesPanel';
import {
  formatAttribute,
  formatMultiplicity,
  formatRelationshipEndLabel,
  rangeBoundsPayload,
  validateRangeBounds,
} from './uml-format';

const attribute = (overrides: Partial<AttributeResource>): AttributeResource => ({
  id: 'attr-1',
  name: 'items',
  kind: 'ATTRIBUTE',
  visibility: 'PUBLIC',
  multiplicity: 'EXACTLY_ONE',
  defaultValue: null,
  typeReferenceId: null,
  type: 'String',
  ordinal: 0,
  ends: [],
  constraints: [],
  ...overrides,
} as AttributeResource);

const classifier = (attrs: AttributeResource[]): ClassifierResource => ({
  id: 'class-1',
  name: 'Order',
  kind: 'CLASS',
  visibility: 'PUBLIC',
  attributes: attrs,
  operations: [],
  constraints: [],
} as unknown as ClassifierResource);

const relationship = (sourceOverrides = {}, targetOverrides = {}): RelationshipResource => ({
  id: 'rel-1',
  kind: 'ASSOCIATION',
  name: null,
  ends: [
    { id: 'e1', classifierId: 'class-1', role: null, multiplicity: 'EXACTLY_ONE', navigability: 'UNSPECIFIED', aggregationKind: 'NONE', ordinal: 1, ...sourceOverrides },
    { id: 'e2', classifierId: 'class-2', role: null, multiplicity: 'ZERO_OR_MANY', navigability: 'UNSPECIFIED', aggregationKind: 'NONE', ordinal: 2, ...targetOverrides },
  ],
  constraints: [],
} as RelationshipResource);

const model = (attrs: AttributeResource[], relationships: RelationshipResource[] = []): ModelResource => ({
  id: 'model',
  projectId: 'project',
  revision: 1,
  versionId: 'version',
  views: [],
  classifiers: [classifier(attrs), { ...classifier([]), id: 'class-2', name: 'Line' }],
  relationships,
} as unknown as ModelResource);

function panelFor(target: ModelResource, selection: Parameters<typeof PropertiesPanel>[0]['selection']): string {
  return renderToStaticMarkup(createElement(PropertiesPanel, {
    model: target,
    selection,
    onCommand: () => null,
    onDelete: () => undefined,
    onSelect: () => undefined,
  }));
}

describe('RANGE multiplicity notation', () => {
  it('writes l..u, l..* for an unbounded upper bound and l when both bounds are equal', () => {
    expect(formatMultiplicity('RANGE', { lowerBound: 2, upperBound: 5 })).toBe('2..5');
    expect(formatMultiplicity('RANGE', { lowerBound: 2, upperBound: null })).toBe('2..*');
    expect(formatMultiplicity('RANGE', { lowerBound: 3, upperBound: 3 })).toBe('3');
    expect(formatMultiplicity('RANGE', { lowerBound: 0, upperBound: 1 })).toBe('0..1');
  });

  it('shows an attribute range in brackets and an end range bare', () => {
    expect(formatAttribute(attribute({ multiplicity: 'RANGE', lowerBound: 2, upperBound: 5 }))).toBe('+ items: String [2..5]');
    expect(formatAttribute(attribute({ multiplicity: 'RANGE', lowerBound: 2, upperBound: null }))).toBe('+ items: String [2..*]');
    expect(formatAttribute(attribute({ multiplicity: 'RANGE', lowerBound: 3, upperBound: 3 }))).toBe('+ items: String [3]');
    const end = relationship({ role: 'lines', multiplicity: 'RANGE', lowerBound: 2, upperBound: 5 }).ends[0]!;
    expect(formatRelationshipEndLabel(end)).toBe('lines 2..5');
  });

  it('leaves every non-RANGE output unchanged, even when stale bounds are present', () => {
    expect(formatAttribute(attribute({ multiplicity: 'ZERO_OR_MANY', lowerBound: 2, upperBound: 5 }))).toBe('+ items: String [0..*]');
    expect(formatAttribute(attribute({ multiplicity: 'EXACTLY_ONE' }))).toBe('+ items: String');
    expect(formatRelationshipEndLabel(relationship().ends[0]!)).toBe('1');
    expect(formatMultiplicity('ONE_OR_MANY')).toBe('1..*');
    expect(formatMultiplicity('RANGE')).toBe('');
  });
});

describe('RANGE bounds validation', () => {
  it('accepts a non-negative minimum with an empty or sufficiently large maximum', () => {
    expect(validateRangeBounds('2', '5')).toBeNull();
    expect(validateRangeBounds('2', '')).toBeNull();
    expect(validateRangeBounds('0', '1')).toBeNull();
    expect(validateRangeBounds('3', '3')).toBeNull();
  });

  it('rejects a missing, negative or non-integer minimum and a maximum below max(min, 1)', () => {
    expect(validateRangeBounds('', '5')).not.toBeNull();
    expect(validateRangeBounds('-1', '5')).not.toBeNull();
    expect(validateRangeBounds('1.5', '')).not.toBeNull();
    expect(validateRangeBounds('5', '2')).not.toBeNull();
    expect(validateRangeBounds('0', '0')).not.toBeNull();
    expect(validateRangeBounds('2', 'x')).not.toBeNull();
  });

  it('turns the draft into numeric bounds for a RANGE and explicit nulls for anything else', () => {
    expect(rangeBoundsPayload('RANGE', '2', '5')).toEqual({ lowerBound: 2, upperBound: 5 });
    expect(rangeBoundsPayload('RANGE', '2', ' ')).toEqual({ lowerBound: 2, upperBound: null });
    expect(rangeBoundsPayload('ZERO_OR_MANY', '2', '5')).toEqual({ lowerBound: null, upperBound: null });
  });
});

describe('RANGE multiplicity commands', () => {
  it('carries the bounds on attribute.add and applies them optimistically', () => {
    const start = model([]);
    const envelope = buildModelCommandEnvelope(start, {
      type: 'attribute.add',
      targets: [{ scope: 'classifier', id: 'class-1' }],
      payload: {
        name: 'items', type: { primitiveName: 'String' }, visibility: 'PUBLIC', multiplicity: 'RANGE',
        lowerBound: 2, upperBound: 5, defaultValue: null, ordinal: 0,
      },
    }, () => 'new-attr');
    expect(envelope.payload).toMatchObject({ multiplicity: 'RANGE', lowerBound: 2, upperBound: 5 });
    const added = applyOptimisticCommand(start, envelope).classifiers[0]!.attributes[0]!;
    expect(added).toMatchObject({ multiplicity: 'RANGE', lowerBound: 2, upperBound: 5 });
  });

  it('sets the bounds on attribute.update and clears them when switching away from RANGE', () => {
    const start = model([attribute({ multiplicity: 'EXACTLY_ONE' })]);
    const toRange = buildModelCommandEnvelope(start, {
      type: 'attribute.update',
      targets: [{ scope: 'attribute', id: 'attr-1' }],
      payload: { multiplicity: 'RANGE', ...rangeBoundsPayload('RANGE', '2', '') },
    });
    expect(toRange.payload).toMatchObject({ multiplicity: 'RANGE', lowerBound: 2, upperBound: null });
    const ranged = applyOptimisticCommand(start, toRange);
    expect(formatAttribute(ranged.classifiers[0]!.attributes[0]!)).toBe('+ items: String [2..*]');

    const back = buildModelCommandEnvelope(ranged, {
      type: 'attribute.update',
      targets: [{ scope: 'attribute', id: 'attr-1' }],
      payload: { multiplicity: 'ZERO_OR_ONE', ...rangeBoundsPayload('ZERO_OR_ONE', '2', '') },
    });
    expect(back.payload).toMatchObject({ multiplicity: 'ZERO_OR_ONE', lowerBound: null, upperBound: null });
    const cleared = applyOptimisticCommand(ranged, back).classifiers[0]!.attributes[0]!;
    expect(cleared).toMatchObject({ multiplicity: 'ZERO_OR_ONE', lowerBound: null, upperBound: null });
  });

  it('sends null bounds on relationship.create ends', () => {
    const request = buildRelationshipCreateRequest('class-1', 'class-2', 'ASSOCIATION');
    const payload = request.payload as { source: object; target: object };
    expect(payload.source).toMatchObject({ multiplicity: 'EXACTLY_ONE', lowerBound: null, upperBound: null });
    expect(payload.target).toMatchObject({ multiplicity: 'EXACTLY_ONE', lowerBound: null, upperBound: null });
  });

  it('carries the end bounds on relationship.update and applies and clears them optimistically', () => {
    const rel = relationship();
    const start = model([], [rel]);
    const toRange = buildModelCommandEnvelope(start, buildRelationshipUpdateRequest(rel, {
      name: null,
      kind: 'ASSOCIATION',
      source: { role: null, multiplicity: 'RANGE', ...rangeBoundsPayload('RANGE', '2', '5'), navigability: 'UNSPECIFIED' },
      target: { role: null, multiplicity: 'ZERO_OR_MANY', ...rangeBoundsPayload('ZERO_OR_MANY', '', ''), navigability: 'UNSPECIFIED' },
    }));
    expect(toRange.payload.source).toMatchObject({ multiplicity: 'RANGE', lowerBound: 2, upperBound: 5 });
    expect(toRange.payload.target).toMatchObject({ lowerBound: null, upperBound: null });
    const ranged = applyOptimisticCommand(start, toRange);
    expect(formatRelationshipEndLabel(ranged.relationships[0]!.ends[0]!)).toBe('2..5');

    const cleared = multiplicityUpdateRequest(ranged.relationships[0]!, 1, 'ONE_OR_MANY')!;
    expect((cleared.payload as { source: object }).source).toEqual({
      role: null, multiplicity: 'ONE_OR_MANY', lowerBound: null, upperBound: null, navigability: 'UNSPECIFIED',
    });
  });

  it('keeps the context-menu payload of a non-RANGE relationship free of bounds', () => {
    const request = multiplicityUpdateRequest(relationship(), 1, 'ZERO_OR_ONE')!;
    expect(JSON.stringify((request.payload as { source: object }).source))
      .toBe(JSON.stringify({ role: null, multiplicity: 'ZERO_OR_ONE', navigability: 'UNSPECIFIED' }));
  });
});

describe('RANGE multiplicity in the properties panel', () => {
  const attributeSelection = { kind: 'attribute', classifierId: 'class-1', elementId: 'attr-1' } as const;
  const relationshipSelection = { kind: 'relationship', relationshipId: 'rel-1' } as const;

  it('offers Rango on attributes and relationship ends but no bound inputs for a fixed multiplicity', () => {
    const attributeMarkup = panelFor(model([attribute({ multiplicity: 'ZERO_OR_ONE' })]), attributeSelection);
    expect(attributeMarkup).toContain('Rango');
    expect(attributeMarkup).not.toContain('Mínimo');
    const relationshipMarkup = panelFor(model([], [relationship()]), relationshipSelection);
    expect(relationshipMarkup).toContain('Rango');
    expect(relationshipMarkup).not.toContain('Mínimo');
  });

  it('shows Mínimo and Máximo with the stored bounds for a valid attribute range', () => {
    const markup = panelFor(model([attribute({ multiplicity: 'RANGE', lowerBound: 2, upperBound: 5 })]), attributeSelection);
    expect(markup).toContain('Mínimo');
    expect(markup).toContain('Máximo');
    expect(markup).toContain('value="2"');
    expect(markup).toContain('value="5"');
    expect(markup).not.toContain('role="alert"');
    expect(markup).not.toMatch(/<button[^>]*disabled=""[^>]*>Guardar atributo/);
  });

  it('shows the inline message and disables saving for an invalid attribute range', () => {
    const markup = panelFor(model([attribute({ multiplicity: 'RANGE', lowerBound: 5, upperBound: 2 })]), attributeSelection);
    expect(markup).toContain('role="alert"');
    expect(markup).toContain('El máximo debe ser mayor o igual al mínimo');
    expect(markup).toMatch(/<button[^>]*disabled=""[^>]*>Guardar atributo/);
  });

  it('shows the range inputs and rejects invalid bounds on a relationship end', () => {
    const ok = panelFor(model([], [relationship({ multiplicity: 'RANGE', lowerBound: 2, upperBound: null })]), relationshipSelection);
    expect(ok).toContain('relationship-source-lower-bound');
    expect(ok).not.toContain('role="alert"');
    const bad = panelFor(model([], [relationship({}, { multiplicity: 'RANGE', lowerBound: null, upperBound: null })]), relationshipSelection);
    expect(bad).toContain('El mínimo debe ser un entero');
    expect(bad).toMatch(/<button[^>]*disabled=""[^>]*>Guardar relación/);
  });
});
