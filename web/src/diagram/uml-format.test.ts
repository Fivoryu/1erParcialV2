import { describe, expect, it } from 'vitest';
import type { RelationshipEndResource } from '../api/types';
import { formatAttribute, formatRelationshipEndLabel, formatRelationshipName, formatStereotype } from './uml-format';

const end = (overrides: Partial<RelationshipEndResource>): RelationshipEndResource => ({
  id: 'end',
  relationshipId: 'rel',
  classifierId: 'class',
  ordinal: 0,
  role: null,
  multiplicity: 'EXACTLY_ONE',
  navigability: 'UNSPECIFIED',
  ...overrides,
} as RelationshipEndResource);

describe('formatRelationshipEndLabel', () => {
  it('shows a multiplicity of exactly one, so an end is never left unspecified', () => {
    expect(formatRelationshipEndLabel(end({ multiplicity: 'EXACTLY_ONE' }))).toBe('1');
  });

  it('shows the multiplicity bare, without attribute brackets, after the role', () => {
    expect(formatRelationshipEndLabel(end({ role: 'orders', multiplicity: 'ZERO_OR_MANY' }))).toBe('orders 0..*');
    expect(formatRelationshipEndLabel(end({ multiplicity: 'ONE_OR_MANY' }))).toBe('1..*');
    expect(formatRelationshipEndLabel(end({ multiplicity: 'ZERO_OR_ONE' }))).toBe('0..1');
  });
});

describe('attribute multiplicity keeps its own notation', () => {
  it('still brackets the bounds and leaves out the default of one', () => {
    const attribute = { name: 'tags', type: 'String', visibility: 'PUBLIC', multiplicity: 'ZERO_OR_MANY', defaultValue: null };
    expect(formatAttribute(attribute as never)).toBe('+ tags: String [0..*]');
    expect(formatAttribute({ ...attribute, multiplicity: 'EXACTLY_ONE' } as never)).toBe('+ tags: String');
  });
});

describe('formatStereotype', () => {
  it('writes a stereotype in guillemets, lower case, as UML 2.5 does', () => {
    expect(formatStereotype('USE')).toBe('«use»');
    expect(formatStereotype('INSTANTIATE')).toBe('«instantiate»');
  });

  it('writes nothing for no stereotype', () => {
    expect(formatStereotype(null)).toBe('');
    expect(formatStereotype(undefined)).toBe('');
    expect(formatStereotype('')).toBe('');
  });
});

describe('formatRelationshipName', () => {
  it('puts the stereotype in front of the name, as UML 2.5 labels a dependency', () => {
    expect(formatRelationshipName('needs', 'USE')).toBe('«use» needs');
  });

  it('shows the stereotype alone for a dependency with no name', () => {
    expect(formatRelationshipName(null, 'IMPORT')).toBe('«import»');
    expect(formatRelationshipName('', 'IMPORT')).toBe('«import»');
  });

  it('shows the plain name when there is no stereotype, and nothing when there is neither', () => {
    expect(formatRelationshipName('owns', null)).toBe('owns');
    expect(formatRelationshipName(null, undefined)).toBe('');
  });
});
