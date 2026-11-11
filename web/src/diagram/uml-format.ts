import type {
  AttributeResource,
  ClassifierResource,
  OperationResource,
  ParameterResource,
  RelationshipEndResource,
} from '../api/types';

const MULTIPLICITY_NOTATION: Record<string, string> = {
  EXACTLY_ONE: '1',
  ZERO_OR_ONE: '0..1',
  ONE_OR_MANY: '1..*',
  ZERO_OR_MANY: '0..*',
};

/**
 * UML treats a multiplicity of exactly one as the default, so it is left out of the
 * notation: writing `[1]` on every member and every end is noise that reads as a
 * technicality rather than as a model. Omitting a bound still means one.
 */
const UML_DEFAULT_MULTIPLICITY = 'EXACTLY_ONE';

export const RANGE_MULTIPLICITY = 'RANGE';

/** The numeric bounds of a RANGE multiplicity; `upperBound` null means unbounded (`*`). */
export interface RangeBounds {
  lowerBound?: number | null;
  upperBound?: number | null;
}

/** `2..5`, `2..*`, or just `3` when both bounds are equal. Empty while the lower bound is missing. */
export function formatRange({ lowerBound, upperBound }: RangeBounds): string {
  if (lowerBound === null || lowerBound === undefined) {
    return '';
  }
  if (upperBound === null || upperBound === undefined) {
    return `${lowerBound}..*`;
  }
  return lowerBound === upperBound ? `${lowerBound}` : `${lowerBound}..${upperBound}`;
}

export function formatMultiplicity(multiplicity: string, bounds?: RangeBounds): string {
  if (multiplicity === RANGE_MULTIPLICITY) {
    return bounds ? formatRange(bounds) : '';
  }
  return MULTIPLICITY_NOTATION[multiplicity] ?? '';
}

/**
 * Mirrors the backend rule for a RANGE: the lower bound is a non-negative integer and the
 * upper bound is empty (unbounded) or at least max(lower, 1). Returns a Spanish message, or null.
 */
export function validateRangeBounds(lowerText: string, upperText: string): string | null {
  const lower = lowerText.trim();
  const upper = upperText.trim();
  if (!/^\d+$/.test(lower)) {
    return 'El mínimo debe ser un entero mayor o igual a 0.';
  }
  if (upper === '') {
    return null;
  }
  if (!/^\d+$/.test(upper)) {
    return 'El máximo debe ser un entero o quedar vacío (ilimitado).';
  }
  if (Number(upper) < Math.max(Number(lower), 1)) {
    return 'El máximo debe ser mayor o igual al mínimo y a 1.';
  }
  return null;
}

/** The payload bounds for a multiplicity choice: numbers for a RANGE, explicit nulls otherwise. */
export function rangeBoundsPayload(
  multiplicity: string,
  lowerText: string,
  upperText: string,
): { lowerBound: number | null; upperBound: number | null } {
  if (multiplicity !== RANGE_MULTIPLICITY) {
    return { lowerBound: null, upperBound: null };
  }
  const upper = upperText.trim();
  return { lowerBound: Number(lowerText.trim()), upperBound: upper === '' ? null : Number(upper) };
}

/** The bounds a copy of a member or end must carry: only a RANGE has any, every other payload stays as it was. */
export function rangeBounds(source: RangeBounds & { multiplicity: string }): RangeBounds {
  return source.multiplicity === RANGE_MULTIPLICITY
    ? { lowerBound: source.lowerBound ?? null, upperBound: source.upperBound ?? null }
    : {};
}

function formatBounds(multiplicity: string, prefix: string, bounds?: RangeBounds): string {
  if (multiplicity === UML_DEFAULT_MULTIPLICITY) {
    return '';
  }
  const notation = formatMultiplicity(multiplicity, bounds);
  return notation ? `${prefix}${notation}]` : '';
}

/** A stereotype as UML 2.5 writes it, in guillemets and lower case: `«use»`. Nothing for none. */
export function formatStereotype(stereotype: string | null | undefined): string {
  return stereotype ? `«${stereotype.toLowerCase()}»` : '';
}

/** The label on the line: the stereotype first, then the name, either of them optional. */
export function formatRelationshipName(name: string | null | undefined, stereotype: string | null | undefined): string {
  return [formatStereotype(stereotype), name?.trim() ?? ''].filter(Boolean).join(' ');
}

/**
 * Only the three association-like kinds carry end labels. UML gives a generalization, a realization
 * and a dependency no role or multiplicity, so a `1` there would be noise that reads as a constraint.
 */
export function endLabelsApplyTo(kind: string): boolean {
  return kind === 'ASSOCIATION' || kind === 'AGGREGATION' || kind === 'COMPOSITION';
}

/**
 * An association end shows its multiplicity bare (`0..*`), always, `1` included: the brackets
 * are attribute notation, and on a relationship an absent number reads as unspecified, not as one.
 */
export function formatRelationshipEndLabel(end: RelationshipEndResource): string {
  const role = end.role?.trim() ?? '';
  return [role, formatMultiplicity(end.multiplicity, end), formatEndProperties(end)].filter(Boolean).join(' ');
}

/** UML end properties, in the order `{ordered, unique}` would list them; nothing when neither is set. */
export function formatEndProperties(end: { isOrdered?: boolean; isUnique?: boolean }): string {
  return [end.isOrdered ? '{ordered}' : '', end.isUnique ? '{unique}' : ''].filter(Boolean).join(' ');
}

export function formatVisibility(visibility: string): string {
  switch (visibility) {
    case 'PUBLIC':
      return '+';
    case 'PRIVATE':
      return '-';
    case 'PROTECTED':
      return '#';
    case 'PACKAGE':
      return '~';
    default:
      return '';
  }
}

export function orderedAttributes(classifier: ClassifierResource): AttributeResource[] {
  return [...classifier.attributes].sort((left, right) => left.ordinal - right.ordinal);
}

export function orderedOperations(classifier: ClassifierResource): OperationResource[] {
  return [...classifier.operations].sort((left, right) => left.ordinal - right.ordinal);
}

/** A member's stereotype goes before the visibility symbol; nothing at all when it has none. */
function memberStereotypePrefix(stereotype: string | null | undefined): string {
  return stereotype ? `${formatStereotype(stereotype)} ` : '';
}

function formatType(type: string | null): string {
  return type ? `: ${type}` : '';
}

export function formatAttribute(attribute: AttributeResource): string {
  const type = formatType(attribute.type);
  const bounds = formatBounds(attribute.multiplicity, ' [', attribute);
  const defaultValue = attribute.defaultValue === null ? '' : ` = ${attribute.defaultValue}`;
  const derived = attribute.isDerived ? '/' : '';
  return `${memberStereotypePrefix(attribute.stereotype)}${formatVisibility(attribute.visibility)} ${derived}${attribute.name}${type}${bounds}${defaultValue}`;
}

function formatParameter(parameter: ParameterResource): string {
  const type = formatType(parameter.type);
  return `${parameter.name}${type}`;
}

export function formatOperation(operation: OperationResource): string {
  const parameters = [...operation.parameters]
    .sort((left, right) => left.ordinal - right.ordinal)
    .map(formatParameter)
    .join(', ');
  const returnType = formatType(operation.returnType);
  return `${memberStereotypePrefix(operation.stereotype)}${formatVisibility(operation.visibility)} ${operation.name}(${parameters})${returnType}`;
}

export function operationNameIsAbstract(operation: OperationResource): boolean {
  return operation.isAbstract;
}

export function attributeIsStatic(attribute: AttributeResource): boolean {
  return attribute.isStatic === true;
}

export function operationIsStatic(operation: OperationResource): boolean {
  return operation.isStatic;
}

/** First line of a note, shortened, so lists and labels can name it without the whole body. */
export function noteSummary(body: string, maxLength = 40): string {
  const firstLine = body.trim().split(/\r?\n/)[0] ?? '';
  if (firstLine === '') {
    return '(nota vacía)';
  }
  return firstLine.length > maxLength ? `${firstLine.slice(0, maxLength - 1)}…` : firstLine;
}
