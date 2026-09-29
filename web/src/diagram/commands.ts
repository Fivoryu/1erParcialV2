import { buildOperationEnvelope, type UuidFactory } from '../api/envelope';
import type {
  AttributeResource,
  ClassifierResource,
  CommandPrecondition,
  CommandTarget,
  ModelResource,
  OperationEnvelope,
  OperationResource,
} from '../api/types';

export type ClassifierKind = 'CLASS' | 'ABSTRACT_CLASS' | 'INTERFACE';

export interface ModelCommandRequest {
  type: string;
  targets?: CommandTarget[];
  preconditions?: CommandPrecondition[];
  payload?: Record<string, unknown>;
}

function nextClassifierName(model: ModelResource, kind: ClassifierKind): string {
  const prefix = kind === 'ABSTRACT_CLASS' ? 'Abstract class' : kind === 'INTERFACE' ? 'Interface' : 'Class';
  const existingNames = new Set(model.classifiers.map((classifier) => classifier.name));
  let index = model.classifiers.length + 1;
  let candidate = `${prefix} ${index}`;
  while (existingNames.has(candidate)) {
    index += 1;
    candidate = `${prefix} ${index}`;
  }
  return candidate;
}

export function buildClassifierCreateEnvelope(
  model: ModelResource,
  kind: ClassifierKind,
  uuidFactory?: UuidFactory,
): OperationEnvelope {
  return buildOperationEnvelope({
    type: 'classifier.create',
    projectId: model.projectId,
    modelId: model.id,
    baseRevision: model.revision,
    payload: {
      name: nextClassifierName(model, kind),
      kind,
      namespaceId: null,
    },
  }, uuidFactory);
}

export function buildModelCommandEnvelope(
  model: ModelResource,
  request: ModelCommandRequest,
  uuidFactory?: UuidFactory,
): OperationEnvelope {
  return buildOperationEnvelope({
    type: request.type,
    projectId: model.projectId,
    modelId: model.id,
    baseRevision: model.revision,
    ...(request.targets ? { targets: request.targets } : {}),
    ...(request.preconditions ? { preconditions: request.preconditions } : {}),
    ...(request.payload ? { payload: request.payload } : {}),
  }, uuidFactory);
}

export function classifierCascadeCount(model: ModelResource, classifierId: string): number {
  const classifier = model.classifiers.find((candidate) => candidate.id === classifierId);
  if (!classifier) {
    return 0;
  }

  const relatedRelationships = model.relationships.filter((relationship) =>
    relationship.ends.some((end) => end.classifierId === classifierId));
  const relationshipTargets = relatedRelationships.reduce(
    (count, relationship) => count + 1 + relationship.ends.length,
    0,
  );
  const parameters = classifier.operations.reduce((count, operation) => count + operation.parameters.length, 0);
  return 1
    + classifier.attributes.length
    + classifier.operations.length
    + parameters
    + classifier.constraints.length
    + relationshipTargets;
}

function payloadString(envelope: OperationEnvelope, key: string): string | null {
  const value = envelope.payload[key];
  return typeof value === 'string' ? value : null;
}

function payloadBoolean(envelope: OperationEnvelope, key: string): boolean | null {
  const value = envelope.payload[key];
  return typeof value === 'boolean' ? value : null;
}

function targetId(envelope: OperationEnvelope, scope: string): string | null {
  const target = envelope.targets.find((candidate) => candidate.scope.toLowerCase() === scope);
  return target?.id ?? null;
}

function resolveTypeName(model: ModelResource, value: unknown): string | null {
  if (typeof value !== 'object' || value === null) {
    return null;
  }
  const type = value as Record<string, unknown>;
  if (typeof type.primitiveName === 'string') {
    return type.primitiveName;
  }
  if (typeof type.classifierId === 'string') {
    return model.classifiers.find((classifier) => classifier.id === type.classifierId)?.name ?? null;
  }
  return null;
}

function ownerClassifierId(model: ModelResource, scope: 'attribute' | 'operation' | 'constraint', elementId: string): string | null {
  const owner = model.classifiers.find((classifier) => {
    if (scope === 'attribute') {
      return classifier.attributes.some((attribute) => attribute.id === elementId);
    }
    if (scope === 'operation') {
      return classifier.operations.some((operation) => operation.id === elementId);
    }
    return classifier.constraints.some((constraint) => constraint.id === elementId);
  });
  return owner?.id ?? null;
}

function updateClassifier(
  model: ModelResource,
  classifierId: string,
  update: (classifier: ClassifierResource) => ClassifierResource,
): ModelResource {
  return {
    ...model,
    classifiers: model.classifiers.map((classifier) =>
      classifier.id === classifierId ? update(classifier) : classifier),
  };
}

function addAttribute(model: ModelResource, envelope: OperationEnvelope, classifierId: string): ModelResource {
  const id = payloadString(envelope, 'id');
  const name = payloadString(envelope, 'name');
  if (!id || !name) {
    return model;
  }
  const attribute: AttributeResource = {
    id,
    name,
    kind: 'ATTRIBUTE',
    visibility: payloadString(envelope, 'visibility') ?? 'PUBLIC',
    multiplicity: payloadString(envelope, 'multiplicity') ?? 'EXACTLY_ONE',
    defaultValue: payloadString(envelope, 'defaultValue'),
    typeReferenceId: null,
    type: resolveTypeName(model, envelope.payload.type),
    ordinal: typeof envelope.payload.ordinal === 'number' ? envelope.payload.ordinal : 0,
  };
  return updateClassifier(model, classifierId, (classifier) => ({
    ...classifier,
    attributes: [...classifier.attributes, attribute],
  }));
}

function addOperation(model: ModelResource, envelope: OperationEnvelope, classifierId: string): ModelResource {
  const id = payloadString(envelope, 'id');
  const name = payloadString(envelope, 'name');
  if (!id || !name) {
    return model;
  }
  const operation: OperationResource = {
    id,
    name,
    visibility: payloadString(envelope, 'visibility') ?? 'PUBLIC',
    isAbstract: payloadBoolean(envelope, 'isAbstract') ?? false,
    isStatic: payloadBoolean(envelope, 'isStatic') ?? false,
    returnTypeReferenceId: null,
    returnType: resolveTypeName(model, envelope.payload.returnType),
    ordinal: typeof envelope.payload.ordinal === 'number' ? envelope.payload.ordinal : 0,
    parameters: [],
  };
  return updateClassifier(model, classifierId, (classifier) => ({
    ...classifier,
    operations: [...classifier.operations, operation],
  }));
}

export function applyOptimisticCommand(model: ModelResource, envelope: OperationEnvelope): ModelResource {
  const classifierId = targetId(envelope, 'classifier');

  switch (envelope.type) {
    case 'classifier.create': {
      const id = payloadString(envelope, 'id');
      const name = payloadString(envelope, 'name');
      const kind = payloadString(envelope, 'kind');
      if (!id || !name || !kind) {
        return model;
      }
      return {
        ...model,
        classifiers: [...model.classifiers, {
          id,
          name,
          kind,
          isAbstract: kind === 'ABSTRACT_CLASS',
          namespaceId: null,
          attributes: [],
          operations: [],
          constraints: [],
        }],
      };
    }
    case 'classifier.rename': {
      if (!classifierId) {
        return model;
      }
      const name = payloadString(envelope, 'name');
      return updateClassifier(model, classifierId, (classifier) => ({ ...classifier, name: name ?? classifier.name }));
    }
    case 'classifier.setAbstract': {
      if (!classifierId) {
        return model;
      }
      const isAbstract = payloadBoolean(envelope, 'isAbstract');
      if (isAbstract === null) {
        return model;
      }
      return updateClassifier(model, classifierId, (classifier) => ({
        ...classifier,
        isAbstract,
        kind: isAbstract ? 'ABSTRACT_CLASS' : classifier.kind === 'ABSTRACT_CLASS' ? 'CLASS' : classifier.kind,
      }));
    }
    case 'attribute.add':
      return classifierId ? addAttribute(model, envelope, classifierId) : model;
    case 'attribute.update': {
      const attributeId = targetId(envelope, 'attribute');
      const ownerId = attributeId ? ownerClassifierId(model, 'attribute', attributeId) : null;
      if (!attributeId || !ownerId) {
        return model;
      }
      return updateClassifier(model, ownerId, (classifier) => ({
        ...classifier,
        attributes: classifier.attributes.map((attribute) => {
          if (attribute.id !== attributeId) {
            return attribute;
          }
          const type = Object.hasOwn(envelope.payload, 'type')
            ? resolveTypeName(model, envelope.payload.type)
            : attribute.type;
          return {
            ...attribute,
            name: payloadString(envelope, 'name') ?? attribute.name,
            visibility: payloadString(envelope, 'visibility') ?? attribute.visibility,
            multiplicity: payloadString(envelope, 'multiplicity') ?? attribute.multiplicity,
            defaultValue: Object.hasOwn(envelope.payload, 'defaultValue')
              ? payloadString(envelope, 'defaultValue')
              : attribute.defaultValue,
            type,
          };
        }),
      }));
    }
    case 'operation.add':
      return classifierId ? addOperation(model, envelope, classifierId) : model;
    case 'operation.update': {
      const operationId = targetId(envelope, 'operation');
      const ownerId = operationId ? ownerClassifierId(model, 'operation', operationId) : null;
      if (!operationId || !ownerId) {
        return model;
      }
      return updateClassifier(model, ownerId, (classifier) => ({
        ...classifier,
        operations: classifier.operations.map((operation) => {
          if (operation.id !== operationId) {
            return operation;
          }
          const returnType = Object.hasOwn(envelope.payload, 'returnType')
            ? resolveTypeName(model, envelope.payload.returnType)
            : operation.returnType;
          return {
            ...operation,
            name: payloadString(envelope, 'name') ?? operation.name,
            visibility: payloadString(envelope, 'visibility') ?? operation.visibility,
            isAbstract: payloadBoolean(envelope, 'isAbstract') ?? operation.isAbstract,
            isStatic: payloadBoolean(envelope, 'isStatic') ?? operation.isStatic,
            returnType,
          };
        }),
      }));
    }
    case 'constraint.add': {
      const id = payloadString(envelope, 'id');
      const ownerId = payloadString(envelope, 'ownerId');
      const expression = payloadString(envelope, 'expression');
      if (!id || !ownerId || !expression) {
        return model;
      }
      return updateClassifier(model, ownerId, (classifier) => ({
        ...classifier,
        constraints: [...classifier.constraints, {
          id,
          expression,
          severity: payloadString(envelope, 'severity') ?? 'ERROR',
        }],
      }));
    }
    case 'classifier.delete': {
      if (!classifierId) {
        return model;
      }
      const removedRelationships = new Set(model.relationships
        .filter((relationship) => relationship.ends.some((end) => end.classifierId === classifierId))
        .map((relationship) => relationship.id));
      return {
        ...model,
        classifiers: model.classifiers.filter((classifier) => classifier.id !== classifierId),
        relationships: model.relationships.filter((relationship) => !removedRelationships.has(relationship.id)),
      };
    }
    case 'attribute.remove':
    case 'operation.remove':
    case 'constraint.remove': {
      const scope = envelope.type.split('.')[0] ?? '';
      const removedId = targetId(envelope, scope);
      if (!removedId || (scope !== 'attribute' && scope !== 'operation' && scope !== 'constraint')) {
        return model;
      }
      const ownerId = ownerClassifierId(model, scope, removedId);
      if (!ownerId) {
        return model;
      }
      return updateClassifier(model, ownerId, (classifier) => {
        if (scope === 'attribute') {
          return { ...classifier, attributes: classifier.attributes.filter((attribute) => attribute.id !== removedId) };
        }
        if (scope === 'operation') {
          return { ...classifier, operations: classifier.operations.filter((operation) => operation.id !== removedId) };
        }
        return { ...classifier, constraints: classifier.constraints.filter((constraint) => constraint.id !== removedId) };
      });
    }
    default:
      return model;
  }
}
