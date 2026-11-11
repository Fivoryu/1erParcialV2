/**
 * The relation tool of the editor rail: the kinds it offers and the command each one creates.
 * No React here, so the rules stay testable without a DOM.
 */
import type { RelationshipResource } from '../api/types';
import { buildRelationshipCreateRequest, type ModelCommandRequest, type RelationshipKind } from './commands';

/** The UML relationship kinds plus the association class, which is an association carrying a class. */
export type RelationToolKind = RelationshipKind | 'ASSOCIATION_CLASS';

export interface RelationToolChoice {
  kind: RelationToolKind;
  label: string;
  description: string;
}

export const RELATION_TOOL_KINDS: readonly RelationToolChoice[] = [
  { kind: 'ASSOCIATION', label: 'Asociación', description: 'Vincula dos elementos del modelo.' },
  { kind: 'AGGREGATION', label: 'Agregación', description: 'Una parte que puede existir por separado del todo.' },
  { kind: 'COMPOSITION', label: 'Composición', description: 'Una parte que depende del elemento que la contiene.' },
  { kind: 'GENERALIZATION', label: 'Generalización', description: 'Herencia: va de la subclase a su clase padre.' },
  { kind: 'INTERFACE_REALIZATION', label: 'Realización de interfaz', description: 'Va de la clase que implementa a la interfaz.' },
  { kind: 'DEPENDENCY', label: 'Dependencia', description: 'Un elemento usa a otro; la flecha apunta al destino.' },
  {
    kind: 'ASSOCIATION_CLASS',
    label: 'Clase de asociación',
    description: 'Una asociación con una clase propia, unida con una línea discontinua.',
  },
];

export function relationToolChoice(kind: RelationToolKind): RelationToolChoice {
  return RELATION_TOOL_KINDS.find((choice) => choice.kind === kind) ?? RELATION_TOOL_KINDS[0]!;
}

export interface RelationToolInput {
  kind: RelationToolKind;
  sourceId: string;
  targetId: string;
  associationClassId?: string;
}

/**
 * The relationship.create for the tool, or null while the input is incomplete. A reflexive
 * relationship (same origin and target) is valid UML and stays allowed.
 */
export function buildRelationToolRequest(input: RelationToolInput): ModelCommandRequest | null {
  if (!input.sourceId || !input.targetId) {
    return null;
  }
  if (input.kind !== 'ASSOCIATION_CLASS') {
    return buildRelationshipCreateRequest(input.sourceId, input.targetId, input.kind);
  }
  const classId = input.associationClassId;
  if (!classId || classId === input.sourceId || classId === input.targetId) {
    return null;
  }
  const request = buildRelationshipCreateRequest(input.sourceId, input.targetId, 'ASSOCIATION');
  return { ...request, payload: { ...request.payload, associationClassId: classId } };
}

/** A not-yet-created relationship between two classes, for rules that read one (association class candidates). */
export function draftRelationship(sourceId: string, targetId: string): RelationshipResource {
  const end = (classifierId: string, ordinal: number) => ({
    id: `draft-${ordinal}`,
    classifierId,
    role: null,
    multiplicity: 'EXACTLY_ONE',
    lowerBound: null,
    upperBound: null,
    navigability: 'UNSPECIFIED',
    aggregationKind: 'NONE',
    ordinal,
  });
  return { id: 'draft', kind: 'ASSOCIATION', name: null, ends: [end(sourceId, 1), end(targetId, 2)], constraints: [] };
}
