import type { AssistantPlanOperationResource } from '../api/assistant';
import type { ModelResource } from '../api/types';

export interface AttributeName {
  name: string;
  ownerId: string;
  ownerName: string;
  type: string | null;
}

export interface OperationName {
  name: string;
  ownerId: string;
  ownerName: string;
}

export interface RelationshipName {
  sourceName: string | null;
  targetName: string | null;
  kind: string;
}

/** Name-only lookup tables for elements in the current live diagram model. */
export interface DiagramNames {
  classifiers: ReadonlyMap<string, string>;
  attributes: ReadonlyMap<string, AttributeName>;
  operations: ReadonlyMap<string, OperationName>;
  relationships: ReadonlyMap<string, RelationshipName>;
}

/** Build a fresh, side-effect-free lookup index from the model currently shown in the editor. */
export function createDiagramNames(model: ModelResource): DiagramNames {
  const classifiers = new Map<string, string>();
  const attributes = new Map<string, AttributeName>();
  const operations = new Map<string, OperationName>();
  const relationships = new Map<string, RelationshipName>();

  for (const classifier of model.classifiers) {
    classifiers.set(classifier.id, classifier.name);
    for (const attribute of classifier.attributes) {
      attributes.set(attribute.id, {
        name: attribute.name,
        ownerId: classifier.id,
        ownerName: classifier.name,
        type: attribute.type,
      });
    }
    for (const operation of classifier.operations) {
      operations.set(operation.id, { name: operation.name, ownerId: classifier.id, ownerName: classifier.name });
    }
  }

  for (const relationship of model.relationships) {
    const ends = [...relationship.ends].sort((left, right) => left.ordinal - right.ordinal);
    relationships.set(relationship.id, {
      sourceName: ends[0] ? classifiers.get(ends[0].classifierId) ?? null : null,
      targetName: ends[1] ? classifiers.get(ends[1].classifierId) ?? null : null,
      kind: relationship.kind,
    });
  }

  return { classifiers, attributes, operations, relationships };
}

const UUID_PATTERN = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/i;
const PLACEHOLDER_PATTERN = /elemento nuevo/i;

function safeName(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  const name = value.trim();
  return name.length > 0 && !UUID_PATTERN.test(name) && !PLACEHOLDER_PATTERN.test(name) ? name : null;
}

function quoted(value: string): string {
  return `"${value}"`;
}

function argumentName(value: unknown): string | null {
  if (typeof value === 'string') {
    return safeName(value);
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return null;
  }
  const candidate = value as Record<string, unknown>;
  return safeName(candidate['name']) ?? safeName(candidate['displayName'])
    ?? safeName(candidate['classifierName']) ?? safeName(candidate['targetClassifierName']);
}

function idArgument(value: unknown): string | null {
  if (typeof value === 'string') {
    return value;
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return null;
  }
  const candidate = value as Record<string, unknown>;
  for (const key of ['id', 'classifierId', 'elementId', 'targetId']) {
    if (typeof candidate[key] === 'string') {
      return candidate[key] as string;
    }
  }
  return null;
}

function classifierNameForValue(value: unknown, names: DiagramNames): string | null {
  const directName = argumentName(value);
  if (directName) {
    return directName;
  }
  const id = idArgument(value);
  return id ? safeName(names.classifiers.get(id)) : null;
}

function classifierNameForOperation(operation: AssistantPlanOperationResource, names: DiagramNames): string | null {
  const targetName = operation.targetId ? safeName(names.classifiers.get(operation.targetId)) : null;
  if (targetName) {
    return targetName;
  }
  const args = operation.args;
  // A classifier created earlier in the same plan is referenced by name, not by a live id.
  for (const key of ['targetClassifierName', 'classifierName', 'targetName']) {
    const name = classifierNameForValue(args[key], names);
    if (name) {
      return name;
    }
  }
  for (const key of ['classifierId', 'ownerClassifierId', 'ownerId']) {
    const id = idArgument(args[key]);
    const name = id ? safeName(names.classifiers.get(id)) : null;
    if (name) {
      return name;
    }
  }
  for (const key of ['classifier', 'owner']) {
    const name = classifierNameForValue(args[key], names);
    if (name) {
      return name;
    }
  }
  return null;
}

function elementIdForOperation(operation: AssistantPlanOperationResource, keys: string[]): string | null {
  if (operation.targetId) {
    return operation.targetId;
  }
  for (const key of keys) {
    const value = operation.args[key];
    if (typeof value === 'string') {
      return value;
    }
    const id = idArgument(value);
    if (id) {
      return id;
    }
  }
  return null;
}

function resolvedNewName(operation: AssistantPlanOperationResource): string | null {
  return safeName(operation.args['name']);
}

function typeName(value: unknown, names: DiagramNames): string | null {
  if (typeof value === 'string') {
    return safeName(value) ?? safeName(names.classifiers.get(value));
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return null;
  }
  const candidate = value as Record<string, unknown>;
  for (const key of ['primitiveName', 'displayName', 'name']) {
    const name = safeName(candidate[key]);
    if (name) {
      return name;
    }
  }
  const typeId = candidate['typeReferenceId'] ?? candidate['classifierId'] ?? candidate['id'];
  return typeof typeId === 'string' ? safeName(names.classifiers.get(typeId)) : null;
}

function attributeType(operation: AssistantPlanOperationResource, names: DiagramNames): string | null {
  return typeName(operation.args['type'] ?? operation.args['typeName'] ?? operation.args['typeReference'], names);
}

function relationshipKind(kind: unknown): string | null {
  if (typeof kind !== 'string') {
    return null;
  }
  const normalized = kind.normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z]/gi, '').toLowerCase();
  if (normalized.includes('composition') || normalized.includes('composite') || normalized.includes('composicion')) {
    return 'Composición';
  }
  if (normalized.includes('aggregation') || normalized.includes('agregacion')) {
    return 'Agregación';
  }
  if (normalized.includes('association') || normalized.includes('asociacion')) {
    return 'Asociación';
  }
  return null;
}

function relationshipPrefix(verb: string, kind: unknown): string {
  const spanishKind = relationshipKind(kind);
  return `${verb} relación${spanishKind ? ` de ${spanishKind}` : ''}`;
}

function relationshipEndName(value: unknown, names: DiagramNames): string | null {
  const directName = argumentName(value);
  if (directName) {
    return directName;
  }
  const id = idArgument(value);
  return id ? safeName(names.classifiers.get(id)) : null;
}

function describeRelationshipEndpoints(
  source: string | null,
  target: string | null,
  prefix: string,
): string {
  if (source && target) {
    return `${prefix} ${quoted(source)} → ${quoted(target)}`;
  }
  if (source) {
    return `${prefix} desde ${quoted(source)} hasta la clase seleccionada`;
  }
  if (target) {
    return `${prefix} desde la clase seleccionada hasta ${quoted(target)}`;
  }
  return `${prefix} entre clases seleccionadas`;
}

function describeRelationship(
  operation: AssistantPlanOperationResource,
  names: DiagramNames,
  action: 'create' | 'update' | 'delete',
): string {
  const id = elementIdForOperation(operation, ['relationshipId', 'id']);
  const relationship = id ? names.relationships.get(id) : undefined;
  const kind = operation.args['kind'] ?? relationship?.kind;
  const prefix = relationshipPrefix(action === 'create' ? 'Crear' : action === 'update' ? 'Actualizar' : 'Eliminar', kind);
  if (action === 'create') {
    return describeRelationshipEndpoints(
      relationshipEndName(operation.args['source'] ?? operation.args['sourceId'], names),
      relationshipEndName(operation.args['target'] ?? operation.args['targetId'], names),
      prefix,
    );
  }
  if (!relationship) {
    const sourceName = relationshipEndName(operation.args['source'] ?? operation.args['sourceId'], names);
    const targetName = relationshipEndName(operation.args['target'] ?? operation.args['targetId'], names);
    if (sourceName || targetName) {
      return describeRelationshipEndpoints(sourceName, targetName, prefix);
    }
    const actionLabel = action === 'delete' ? 'Eliminar' : 'Actualizar';
    const spanishKind = relationshipKind(kind);
    return `${actionLabel} la relación${spanishKind ? ` de ${spanishKind}` : ''} seleccionada`;
  }
  const sourceName = safeName(relationship.sourceName);
  const targetName = safeName(relationship.targetName);
  if (!sourceName && !targetName) {
    const actionLabel = action === 'delete' ? 'Eliminar' : 'Actualizar';
    const spanishKind = relationshipKind(kind);
    return `${actionLabel} la relación${spanishKind ? ` de ${spanishKind}` : ''} seleccionada`;
  }
  return describeRelationshipEndpoints(sourceName, targetName, prefix);
}

/** Describe one planned operation without exposing technical ids or generic placeholders. */
export function describeAssistantOperation(
  operation: AssistantPlanOperationResource,
  names: DiagramNames,
): string {
  const args = operation.args;
  const name = resolvedNewName(operation);
  switch (operation.type) {
    case 'classifier.create':
      return name ? `Crear clase ${quoted(name)}` : 'Crear clase';
    case 'classifier.rename': {
      const classifierName = classifierNameForOperation(operation, names);
      if (classifierName && name) {
        return `Renombrar clase ${quoted(classifierName)} a ${quoted(name)}`;
      }
      if (name) {
        return `Renombrar clase a ${quoted(name)}`;
      }
      return classifierName ? `Renombrar clase ${quoted(classifierName)}` : 'Renombrar clase seleccionada';
    }
    case 'classifier.delete': {
      const classifierName = classifierNameForOperation(operation, names) ?? name;
      return classifierName ? `Eliminar clase ${quoted(classifierName)}` : 'Eliminar clase seleccionada';
    }
    case 'classifier.setAbstract': {
      const classifierName = classifierNameForOperation(operation, names);
      const isAbstract = args['isAbstract'];
      if (classifierName && typeof isAbstract === 'boolean') {
        return `Marcar clase ${quoted(classifierName)} como ${isAbstract ? 'abstracta' : 'concreta'}`;
      }
      return classifierName
        ? `Cambiar abstracción de la clase ${quoted(classifierName)}`
        : 'Cambiar abstracción de la clase seleccionada';
    }
    case 'attribute.add': {
      const attributeName = name;
      const type = attributeType(operation, names);
      const memberName = attributeName && type ? `${attributeName}: ${type}` : attributeName;
      const ownerName = classifierNameForOperation(operation, names);
      if (memberName && ownerName) {
        return `Agregar atributo ${quoted(memberName)} a ${quoted(ownerName)}`;
      }
      if (memberName) {
        return `Agregar atributo ${quoted(memberName)} a la clase seleccionada`;
      }
      return ownerName ? `Agregar atributo a ${quoted(ownerName)}` : 'Agregar atributo a la clase seleccionada';
    }
    case 'attribute.update':
    case 'attribute.remove': {
      const id = elementIdForOperation(operation, ['attributeId', 'id']);
      const attribute = id ? names.attributes.get(id) : undefined;
      const attributeName = safeName(attribute?.name) ?? name;
      const ownerName = safeName(attribute?.ownerName) ?? classifierNameForOperation(operation, names);
      const verb = operation.type === 'attribute.update' ? 'Actualizar' : 'Eliminar';
      if (attributeName && ownerName) {
        return `${verb} atributo ${quoted(attributeName)} de ${quoted(ownerName)}`;
      }
      if (attributeName) {
        return `${verb} atributo ${quoted(attributeName)} de la clase seleccionada`;
      }
      if (ownerName) {
        return `${verb} atributo de ${quoted(ownerName)}`;
      }
      return operation.type === 'attribute.update'
        ? 'Actualizar el atributo seleccionado'
        : 'Eliminar el atributo seleccionado';
    }
    case 'operation.add': {
      const operationName = name;
      const ownerName = classifierNameForOperation(operation, names);
      if (operationName && ownerName) {
        return `Agregar operación ${quoted(operationName)} a ${quoted(ownerName)}`;
      }
      if (operationName) {
        return `Agregar operación ${quoted(operationName)} a la clase seleccionada`;
      }
      return ownerName ? `Agregar operación a ${quoted(ownerName)}` : 'Agregar operación a la clase seleccionada';
    }
    case 'operation.update':
    case 'operation.remove':
    case 'operation.delete': {
      const id = elementIdForOperation(operation, ['operationId', 'id']);
      const member = id ? names.operations.get(id) : undefined;
      const operationName = safeName(member?.name) ?? name;
      const ownerName = safeName(member?.ownerName) ?? classifierNameForOperation(operation, names);
      const verb = operation.type === 'operation.update' ? 'Actualizar' : 'Eliminar';
      if (operationName && ownerName) {
        return `${verb} operación ${quoted(operationName)} de ${quoted(ownerName)}`;
      }
      if (operationName) {
        return `${verb} operación ${quoted(operationName)} de la clase seleccionada`;
      }
      return operation.type === 'operation.update'
        ? 'Actualizar la operación seleccionada'
        : 'Eliminar la operación seleccionada';
    }
    case 'relationship.create':
      return describeRelationship(operation, names, 'create');
    case 'relationship.update':
      return describeRelationship(operation, names, 'update');
    case 'relationship.delete':
      return describeRelationship(operation, names, 'delete');
    default:
      return 'Realizar cambio en el modelo';
  }
}
