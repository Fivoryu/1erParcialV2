import type { AttributeResource, ClassifierResource, OperationResource, ParameterResource } from '../api/types';

const MULTIPLICITY_NOTATION: Record<string, string> = {
  EXACTLY_ONE: '1',
  ZERO_OR_ONE: '0..1',
  ONE_OR_MANY: '1..*',
  ZERO_OR_MANY: '0..*',
};

export function formatMultiplicity(multiplicity: string): string {
  return MULTIPLICITY_NOTATION[multiplicity] ?? '';
}

export function formatVisibility(visibility: string): string {
  switch (visibility) {
    case 'PUBLIC':
      return '+';
    case 'PRIVATE':
      return '-';
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
  const multiplicity = formatMultiplicity(attribute.multiplicity);
  const bounds = multiplicity ? ` [${multiplicity}]` : '';
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
