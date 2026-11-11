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
import { UmlClassCard } from './UmlClassNode';
import { formatAttribute, formatEndProperties, formatRelationshipEndLabel } from './uml-format';

const attribute = (overrides: Partial<AttributeResource> = {}): AttributeResource => ({
  id: 'attr-1',
  name: 'age',
  kind: 'ATTRIBUTE',
  visibility: 'PRIVATE',
  multiplicity: 'EXACTLY_ONE',
  defaultValue: null,
  typeReferenceId: null,
  type: 'int',
  ordinal: 0,
  ...overrides,
});

const classifier = (attrs: AttributeResource[], id = 'class-1', name = 'Person'): ClassifierResource => ({
  id,
  name,
  kind: 'CLASS',
  isAbstract: false,
  namespaceId: null,
  attributes: attrs,
  operations: [],
  constraints: [],
});

const relationship = (sourceOverrides = {}, targetOverrides = {}): RelationshipResource => ({
  id: 'rel-1',
  kind: 'ASSOCIATION',
  name: null,
  ends: [
    { id: 'e1', classifierId: 'class-1', role: null, multiplicity: 'EXACTLY_ONE', navigability: 'UNSPECIFIED', aggregationKind: 'NONE', ordinal: 1, ...sourceOverrides },
    { id: 'e2', classifierId: 'class-2', role: 'items', multiplicity: 'ZERO_OR_MANY', navigability: 'UNSPECIFIED', aggregationKind: 'NONE', ordinal: 2, ...targetOverrides },
  ],
  constraints: [],
});

const model = (attrs: AttributeResource[], relationships: RelationshipResource[] = []): ModelResource => ({
  id: 'model',
  projectId: 'project',
  revision: 1,
  versionId: 'version',
  views: [],
  classifiers: [classifier(attrs), classifier([], 'class-2', 'Line')],
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

/** The checkbox that sits right before the given label text, as `<input ...checked.../>`-aware markup. */
function checkboxBefore(html: string, label: string): string {
  const index = html.indexOf(`<span>${label}</span>`);
  expect(index).toBeGreaterThan(-1);
  return html.slice(html.lastIndexOf('<input', index), index);
}

describe('derived and static attribute notation', () => {
  it('prefixes a derived attribute name with a slash, after the visibility', () => {
    expect(formatAttribute(attribute({ isDerived: true }))).toBe('- /age: int');
    expect(formatAttribute(attribute({ isDerived: true, visibility: 'PUBLIC' }))).toBe('+ /age: int');
  });

  it('leaves the text of a plain, a static, and an old-snapshot attribute unchanged', () => {
    expect(formatAttribute(attribute())).toBe('- age: int');
    expect(formatAttribute(attribute({ isStatic: true }))).toBe('- age: int');
    expect(formatAttribute(attribute({ isStatic: false, isDerived: false }))).toBe('- age: int');
  });

  it('underlines a static attribute row on the canvas and nothing else', () => {
    const html = renderToStaticMarkup(createElement(UmlClassCard, {
      classifier: classifier([
        attribute({ id: 'a1', name: 'count', isStatic: true }),
        attribute({ id: 'a2', name: 'other', ordinal: 1 }),
      ]),
    }));
    const rows = html.split('class="uml-member-row').slice(1);
    expect(rows).toHaveLength(2);
    expect(rows[0]).toMatch(/^[^"]*uml-member--static/);
    expect(rows[0]).toContain('- count: int');
    expect(rows[1]).not.toMatch(/^[^"]*uml-member--static/);
  });

  it('shows the slash and the underline together for a static derived attribute', () => {
    const html = renderToStaticMarkup(createElement(UmlClassCard, {
      classifier: classifier([attribute({ isStatic: true, isDerived: true })]),
    }));
    expect(html).toContain('uml-member--static');
    expect(html).toContain('- /age: int');
  });
});

describe('relationship end properties notation', () => {
  it('writes {ordered} and {unique} after the multiplicity', () => {
    const [source, target] = relationship({}, { isOrdered: true }).ends;
    expect(formatRelationshipEndLabel(source!)).toBe('1');
    expect(formatRelationshipEndLabel(target!)).toBe('items 0..* {ordered}');
    expect(formatRelationshipEndLabel({ ...target!, isUnique: true })).toBe('items 0..* {ordered} {unique}');
    expect(formatRelationshipEndLabel({ ...target!, isOrdered: false, isUnique: true })).toBe('items 0..* {unique}');
  });

  it('shows the properties even when the multiplicity and role are empty', () => {
    expect(formatEndProperties({ isOrdered: true })).toBe('{ordered}');
    expect(formatEndProperties({})).toBe('');
    expect(formatRelationshipEndLabel({ ...relationship().ends[0]!, multiplicity: 'UNSPECIFIED', isUnique: true })).toBe('{unique}');
  });
});

describe('attribute flag commands', () => {
  it('carries the flags on attribute.add and applies them optimistically', () => {
    const start = model([]);
    const envelope = buildModelCommandEnvelope(start, {
      type: 'attribute.add',
      targets: [{ scope: 'classifier', id: 'class-1' }],
      payload: { name: 'age', type: { primitiveName: 'int' }, visibility: 'PRIVATE', multiplicity: 'EXACTLY_ONE', isStatic: true, isDerived: true, defaultValue: null, ordinal: 0 },
    }, () => 'new-attr');
    expect(envelope.payload).toMatchObject({ isStatic: true, isDerived: true });
    expect(applyOptimisticCommand(start, envelope).classifiers[0]!.attributes[0]).toMatchObject({ isStatic: true, isDerived: true });
  });

  it('defaults both flags to false when attribute.add omits them', () => {
    const start = model([]);
    const envelope = buildModelCommandEnvelope(start, {
      type: 'attribute.add',
      targets: [{ scope: 'classifier', id: 'class-1' }],
      payload: { name: 'age', type: { primitiveName: 'int' }, ordinal: 0 },
    }, () => 'new-attr');
    expect(applyOptimisticCommand(start, envelope).classifiers[0]!.attributes[0]).toMatchObject({ isStatic: false, isDerived: false });
  });

  it('sets and clears the flags on attribute.update, keeping them when omitted', () => {
    const start = model([attribute()]);
    const on = applyOptimisticCommand(start, buildModelCommandEnvelope(start, {
      type: 'attribute.update',
      targets: [{ scope: 'attribute', id: 'attr-1' }],
      payload: { isStatic: true, isDerived: true },
    }));
    expect(on.classifiers[0]!.attributes[0]).toMatchObject({ isStatic: true, isDerived: true });
    expect(formatAttribute(on.classifiers[0]!.attributes[0]!)).toBe('- /age: int');

    const renamed = applyOptimisticCommand(on, buildModelCommandEnvelope(on, {
      type: 'attribute.update',
      targets: [{ scope: 'attribute', id: 'attr-1' }],
      payload: { visibility: 'PUBLIC' },
    }));
    expect(renamed.classifiers[0]!.attributes[0]).toMatchObject({ isStatic: true, isDerived: true, visibility: 'PUBLIC' });

    const off = applyOptimisticCommand(on, buildModelCommandEnvelope(on, {
      type: 'attribute.update',
      targets: [{ scope: 'attribute', id: 'attr-1' }],
      payload: { isStatic: false, isDerived: false },
    }));
    expect(off.classifiers[0]!.attributes[0]).toMatchObject({ isStatic: false, isDerived: false });
  });
});

describe('relationship end flag commands', () => {
  it('leaves relationship.create ends without flags (server default false)', () => {
    const payload = buildRelationshipCreateRequest('class-1', 'class-2', 'ASSOCIATION').payload as { source: object; target: object };
    expect(payload.source).not.toHaveProperty('isOrdered');
    expect(payload.target).not.toHaveProperty('isUnique');
  });

  it('carries the flags on relationship.update and applies them optimistically per end', () => {
    const rel = relationship();
    const start = model([], [rel]);
    const envelope = buildModelCommandEnvelope(start, buildRelationshipUpdateRequest(rel, {
      name: null,
      kind: 'ASSOCIATION',
      source: { role: null, multiplicity: 'EXACTLY_ONE', navigability: 'UNSPECIFIED', isOrdered: false, isUnique: false },
      target: { role: 'items', multiplicity: 'ZERO_OR_MANY', navigability: 'UNSPECIFIED', isOrdered: true, isUnique: true },
    }));
    expect(envelope.payload.source).toMatchObject({ isOrdered: false, isUnique: false });
    expect(envelope.payload.target).toMatchObject({ isOrdered: true, isUnique: true });
    const applied = applyOptimisticCommand(start, envelope).relationships[0]!;
    expect(applied.ends[0]).toMatchObject({ isOrdered: false, isUnique: false });
    expect(applied.ends[1]).toMatchObject({ isOrdered: true, isUnique: true });
    expect(formatRelationshipEndLabel(applied.ends[1]!)).toBe('items 0..* {ordered} {unique}');
  });

  it('keeps the stored flags when the update omits them (context-menu multiplicity change)', () => {
    const rel = relationship({}, { isOrdered: true, isUnique: true });
    const start = model([], [rel]);
    const request = multiplicityUpdateRequest(rel, 1, 'ZERO_OR_ONE')!;
    expect(JSON.stringify(request.payload)).not.toContain('isOrdered');
    const applied = applyOptimisticCommand(start, buildModelCommandEnvelope(start, request)).relationships[0]!;
    expect(applied.ends[0]).toMatchObject({ multiplicity: 'ZERO_OR_ONE' });
    expect(applied.ends[1]).toMatchObject({ isOrdered: true, isUnique: true });
  });
});

describe('flags in the properties panel', () => {
  const attributeSelection = { kind: 'attribute', classifierId: 'class-1', elementId: 'attr-1' } as const;
  const relationshipSelection = { kind: 'relationship', relationshipId: 'rel-1' } as const;

  it('offers Estático and Derivado on an attribute, unchecked by default', () => {
    const html = panelFor(model([attribute()]), attributeSelection);
    expect(checkboxBefore(html, 'Estático')).not.toContain('checked');
    expect(checkboxBefore(html, 'Derivado')).not.toContain('checked');
  });

  it('checks the boxes that match the stored attribute flags', () => {
    const html = panelFor(model([attribute({ isStatic: true })]), attributeSelection);
    expect(checkboxBefore(html, 'Estático')).toContain('checked');
    expect(checkboxBefore(html, 'Derivado')).not.toContain('checked');
    const derived = panelFor(model([attribute({ isDerived: true })]), attributeSelection);
    expect(checkboxBefore(derived, 'Derivado')).toContain('checked');
  });

  it('does not offer the attribute flags on an enumeration literal', () => {
    const literal = model([attribute()]);
    literal.classifiers[0]!.kind = 'ENUMERATION';
    const html = panelFor(literal, attributeSelection);
    expect(html).not.toContain('Estático');
    expect(html).not.toContain('Derivado');
  });

  it('offers Ordenado and Único once per relationship end, reflecting each end', () => {
    const html = panelFor(model([], [relationship({ isOrdered: true }, { isUnique: true })]), relationshipSelection);
    expect(html.match(/<span>Ordenado<\/span>/g)).toHaveLength(2);
    expect(html.match(/<span>Único<\/span>/g)).toHaveLength(2);
    const [sourceHtml, targetHtml] = html.split('Extremo de destino');
    expect(checkboxBefore(sourceHtml!, 'Ordenado')).toContain('checked');
    expect(checkboxBefore(sourceHtml!, 'Único')).not.toContain('checked');
    expect(checkboxBefore(targetHtml!, 'Ordenado')).not.toContain('checked');
    expect(checkboxBefore(targetHtml!, 'Único')).toContain('checked');
  });
});
