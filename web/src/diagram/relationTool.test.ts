import { describe, expect, it } from 'vitest';
import type { ClassifierResource } from '../api/types';
import { associationClassCandidates } from './associationClass';
import {
  RELATION_TOOL_KINDS,
  buildRelationToolRequest,
  draftRelationship,
  relationToolChoice,
  type RelationToolKind,
} from './relationTool';

function classifier(id: string, name: string, kind = 'CLASS'): ClassifierResource {
  return { id, name, kind, isAbstract: false, namespaceId: null, attributes: [], operations: [], constraints: [] };
}

describe('relation tool kinds', () => {
  it('offers the six UML kinds plus the association class, in the mockup order', () => {
    expect(RELATION_TOOL_KINDS.map((choice) => choice.kind)).toEqual([
      'ASSOCIATION',
      'AGGREGATION',
      'COMPOSITION',
      'GENERALIZATION',
      'INTERFACE_REALIZATION',
      'DEPENDENCY',
      'ASSOCIATION_CLASS',
    ]);
    expect(relationToolChoice('ASSOCIATION_CLASS').label).toBe('Clase de asociación');
  });
});

describe('buildRelationToolRequest', () => {
  it('creates a relationship of the chosen kind from origin to target', () => {
    const request = buildRelationToolRequest({ kind: 'COMPOSITION', sourceId: 's', targetId: 't' });

    expect(request).toEqual(expect.objectContaining({
      type: 'relationship.create',
      targets: [{ scope: 'classifier', id: 's' }, { scope: 'classifier', id: 't' }],
    }));
    expect(request?.payload).toEqual(expect.objectContaining({ kind: 'COMPOSITION' }));
    expect(request?.payload).not.toHaveProperty('associationClassId');
  });

  it('creates an association that carries its class for the association class kind', () => {
    const request = buildRelationToolRequest({ kind: 'ASSOCIATION_CLASS', sourceId: 's', targetId: 't', associationClassId: 'c' });

    expect(request?.payload).toEqual(expect.objectContaining({ kind: 'ASSOCIATION', associationClassId: 'c' }));
  });

  it.each<[string, Parameters<typeof buildRelationToolRequest>[0]]>([
    ['without an origin', { kind: 'ASSOCIATION', sourceId: '', targetId: 't' }],
    ['without a class for the association class kind', { kind: 'ASSOCIATION_CLASS', sourceId: 's', targetId: 't' }],
    ['with the class equal to one end', { kind: 'ASSOCIATION_CLASS', sourceId: 's', targetId: 't', associationClassId: 's' }],
  ])('returns null %s', (_case, input) => {
    expect(buildRelationToolRequest(input)).toBeNull();
  });

  it('allows a reflexive relationship (same origin and target)', () => {
    expect(buildRelationToolRequest({ kind: 'ASSOCIATION' as RelationToolKind, sourceId: 's', targetId: 's' })).not.toBeNull();
  });
});

describe('draftRelationship', () => {
  it('feeds the existing association class rule, which then leaves out both ends', () => {
    const model = {
      id: 'm', projectId: 'p', revision: 1, versionId: 'v', views: [], relationships: [],
      classifiers: [classifier('a', 'A'), classifier('b', 'B'), classifier('c', 'Enrollment'), classifier('i', 'Shape', 'INTERFACE')],
    };

    expect(associationClassCandidates(model, draftRelationship('a', 'b')).map((entry) => entry.id)).toEqual(['c']);
  });
});
