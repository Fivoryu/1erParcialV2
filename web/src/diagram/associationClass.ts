import type { ClassifierResource, ModelResource, RelationshipResource } from '../api/types';

const ASSOCIATION_KINDS = ['ASSOCIATION', 'AGGREGATION', 'COMPOSITION'];

/** Only a plain association, an aggregation or a composition can have an association class. */
export function isAssociationKind(kind: string): boolean {
  return ASSOCIATION_KINDS.includes(kind);
}

/**
 * The classes a relationship may take as its association class: a real class (not an interface, an enumeration
 * or a package), not one of its own ends, and not already the association class of another relationship. The
 * class it has now stays in the list so the selection can show it.
 */
export function associationClassCandidates(model: ModelResource, relationship: RelationshipResource): ClassifierResource[] {
  const ends = new Set(relationship.ends.map((end) => end.classifierId));
  const taken = new Set(model.relationships
    .filter((other) => other.id !== relationship.id)
    .map((other) => other.associationClassId)
    .filter((id): id is string => typeof id === 'string'));
  return model.classifiers.filter((classifier) =>
    (classifier.kind === 'CLASS' || classifier.kind === 'ABSTRACT_CLASS')
    && !ends.has(classifier.id)
    && !taken.has(classifier.id));
}

interface Point {
  x: number;
  y: number;
}

/**
 * The dashed line UML draws from an association to its association class: a straight segment from a point on the
 * association to the nearest point of the class's border. Nothing to draw when that point is on the class.
 */
export function associationClassLink(
  from: Point,
  rectangle: { x: number; y: number; width: number; height: number },
): { from: Point; to: Point } | null {
  const clamp = (value: number, low: number, high: number) => Math.min(high, Math.max(low, value));
  const to = {
    x: clamp(from.x, rectangle.x, rectangle.x + rectangle.width),
    y: clamp(from.y, rectangle.y, rectangle.y + rectangle.height),
  };
  return to.x === from.x && to.y === from.y ? null : { from: { ...from }, to };
}
