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

export function formatMultiplicity(multiplicity: string): string {
  return MULTIPLICITY_NOTATION[multiplicity] ?? '';
}

function formatBounds(multiplicity: string, prefix: string): string {
  if (multiplicity === UML_DEFAULT_MULTIPLICITY) {
    return '';
  }
  const notation = formatMultiplicity(multiplicity);
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
  return [role, formatMultiplicity(end.multiplicity)].filter(Boolean).join(' ');
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

function formatType(type: string | null): string {
  return type ? `: ${type}` : '';
}

export function formatAttribute(attribute: AttributeResource): string {
  const type = formatType(attribute.type);
  const bounds = formatBounds(attribute.multiplicity, ' [');
  const defaultValue = attribute.defaultValue === null ? '' : ` = ${attribute.defaultValue}`;
  return `${formatVisibility(attribute.visibility)} ${attribute.name}${type}${bounds}${defaultValue}`;
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
  return `${formatVisibility(operation.visibility)} ${operation.name}(${parameters})${returnType}`;
}

export function operationNameIsAbstract(operation: OperationResource): boolean {
  return operation.isAbstract;
}

export function operationIsStatic(operation: OperationResource): boolean {
  return operation.isStatic;
}
