import { buildOperationEnvelope, type UuidFactory } from '../api/envelope';
import type { AnchorSide } from './orthogonal-routing';
import type {
  AttributeResource,
  ClassifierResource,
  CommandPrecondition,
  CommandTarget,
  ConstraintResource,
  ConstraintSeverity,
  ModelResource,
  OperationEnvelope,
  OperationResource,
  ParameterResource,
  PersistenceStore,
  RelationshipResource,
  RouteAdjustmentResource,
  ViewStyleResource,
} from '../api/types';

export type ClassifierKind = 'CLASS' | 'ABSTRACT_CLASS' | 'INTERFACE' | 'ENUMERATION' | 'PACKAGE';
export type RelationshipKind =
  | 'ASSOCIATION'
  | 'AGGREGATION'
  | 'COMPOSITION'
  | 'GENERALIZATION'
  | 'INTERFACE_REALIZATION'
  | 'DEPENDENCY';

export const RELATIONSHIP_KINDS: readonly RelationshipKind[] = [
  'ASSOCIATION',
  'AGGREGATION',
  'COMPOSITION',
  'GENERALIZATION',
  'INTERFACE_REALIZATION',
  'DEPENDENCY',
];

/** The UML 2.5 dependency stereotypes, in the order the properties panel offers them. */
export const DEPENDENCY_STEREOTYPES = [
  'USE',
  'IMPORT',
  'ACCESS',
  'INCLUDE',
  'EXTEND',
  'INSTANTIATE',
  'CALL',
  'CREATE',
  'DERIVE',
  'REFINE',
  'TRACE',
] as const;
export type DependencyStereotype = (typeof DEPENDENCY_STEREOTYPES)[number];

export interface ModelCommandRequest {
  type: string;
  targets?: CommandTarget[];
  preconditions?: CommandPrecondition[];
  payload?: Record<string, unknown>;
}

export type TypeReferenceInput = { primitiveName: string } | { classifierId: string };
export type ParameterDirection = 'IN' | 'OUT' | 'INOUT';
export type ConstraintOwnerType = 'CLASSIFIER' | 'RELATIONSHIP';

export function buildConstraintAddRequest(
  ownerType: ConstraintOwnerType,
  ownerId: string,
  expression: string,
  severity: ConstraintSeverity,
): ModelCommandRequest {
  return {
    type: 'constraint.add',
    targets: [{ scope: ownerType.toLowerCase(), id: ownerId }],
    payload: { ownerType, ownerId, expression, severity },
  };
}

export function buildConstraintUpdateRequest(
  constraintId: string,
  values: Partial<Pick<ConstraintResource, 'expression' | 'severity'>>,
): ModelCommandRequest {
  return {
    type: 'constraint.update',
    targets: [{ scope: 'constraint', id: constraintId }],
    payload: { ...values },
  };
}

export function buildConstraintRemoveRequest(constraintId: string): ModelCommandRequest {
  return {
    type: 'constraint.remove',
    targets: [{ scope: 'constraint', id: constraintId }],
    payload: {},
  };
}

export interface ParameterAddValues {
  name: string;
  type: TypeReferenceInput;
  direction: ParameterDirection;
  ordinal: number;
}

export type ParameterUpdateValues = Partial<ParameterAddValues>;

export function buildParameterAddRequest(operationId: string, values: ParameterAddValues): ModelCommandRequest {
  return {
    type: 'parameter.add',
    targets: [{ scope: 'operation', id: operationId }],
    payload: { ...values },
  };
}

export function buildParameterUpdateRequest(parameterId: string, values: ParameterUpdateValues): ModelCommandRequest {
  return {
    type: 'parameter.update',
    targets: [{ scope: 'parameter', id: parameterId }],
    payload: { ...values },
  };
}

export function buildParameterRemoveRequest(parameterId: string): ModelCommandRequest {
  return {
    type: 'parameter.remove',
    targets: [{ scope: 'parameter', id: parameterId }],
    payload: {},
  };
}

function nextClassifierName(model: ModelResource, kind: ClassifierKind): string {
  const prefix = kind === 'ABSTRACT_CLASS' ? 'Abstract class'
    : kind === 'INTERFACE' ? 'Interface'
      : kind === 'ENUMERATION' ? 'Enumeration'
      : kind === 'PACKAGE' ? 'Package'
        : 'Class';
  const existingNames = new Set(model.classifiers.map((classifier) => classifier.name));
  let index = model.classifiers.length + 1;
  let candidate = `${prefix} ${index}`;
  while (existingNames.has(candidate)) {
    index += 1;
    candidate = `${prefix} ${index}`;
  }
  return candidate;
}

export function buildClassifierSetNamespaceRequest(classifierId: string, namespaceId: string | null): ModelCommandRequest {
  return {
    type: 'classifier.setNamespace',
    targets: [{ scope: 'classifier', id: classifierId }],
    payload: { namespaceId },
  };
}

/** A null store hands the choice back to the automatic classifier. */
export function buildClassifierSetStoreRequest(classifierId: string, store: PersistenceStore | null): ModelCommandRequest {
  return {
    type: 'classifier.setStore',
    targets: [{ scope: 'classifier', id: classifierId }],
    payload: { store },
  };
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

export function buildRelationshipCreateRequest(
  sourceClassifierId: string,
  targetClassifierId: string,
  kind: RelationshipKind,
): ModelCommandRequest {
  const end = (classifierId: string) => ({
    classifierId,
    role: null,
    multiplicity: 'EXACTLY_ONE',
    navigability: 'UNSPECIFIED',
  });
  return {
    type: 'relationship.create',
    targets: [
      { scope: 'classifier', id: sourceClassifierId },
      { scope: 'classifier', id: targetClassifierId },
    ],
    payload: {
      kind,
      name: null,
      source: end(sourceClassifierId),
      target: end(targetClassifierId),
      waypoints: [],
    },
  };
}

export interface RelationshipUpdateValues {
  name: string | null;
  kind: RelationshipKind;
  /** Left out, the relationship keeps its stereotype; null removes it. */
  stereotype?: string | null;
  source: { role: string | null; multiplicity: string; navigability: string };
  target: { role: string | null; multiplicity: string; navigability: string };
}

export function buildRelationshipUpdateRequest(
  relationship: RelationshipResource,
  values: RelationshipUpdateValues,
): ModelCommandRequest {
  return {
    type: 'relationship.update',
    targets: [{ scope: 'relationship', id: relationship.id }],
    payload: {
      name: values.name,
      kind: values.kind,
      ...(values.stereotype !== undefined ? { stereotype: values.stereotype } : {}),
      source: values.source,
      target: values.target,
    },
  };
}

export function buildRelationshipDeleteRequest(relationship: RelationshipResource): ModelCommandRequest {
  return {
    type: 'relationship.delete',
    targets: [{ scope: 'relationship', id: relationship.id }],
    preconditions: [{ kind: 'cascade_count', expect: relationship.ends.length + relationship.constraints.length + 1, scope: null }],
    payload: {},
  };
}

export interface ClassifierGeometryUpdate {
  targetType: 'CLASSIFIER';
  viewId: string;
  targetId: string;
  x: number;
  y: number;
  w: number;
  h: number;
  zOrder: number;
}

export interface RelationshipWaypointsUpdate {
  targetType: 'RELATIONSHIP';
  viewId: string;
  targetId: string;
  waypoints: Array<{ x: number; y: number }>;
}

export type GeometryUpdate = ClassifierGeometryUpdate | RelationshipWaypointsUpdate;

export function buildGeometrySetRequest(payload: GeometryUpdate): ModelCommandRequest {
  return { type: 'geometry.set', payload: { ...payload } };
}

export type RoutingMode = 'STRAIGHT' | 'FREE' | 'ORTHOGONAL';

/** A relationship with no stored style row is drawn orthogonally, the UML 2.5 class-diagram convention. */
export const DEFAULT_ROUTING: RoutingMode = 'ORTHOGONAL';

/** The appearance fields plus the adjustment, without the (view, target) identity. */
export interface ViewStyleValues {
  routing: RoutingMode;
  cornerRadius: number;
  strokeWidth: number;
  dashed: boolean;
  routeAdjustment: RouteAdjustmentResource | null;
}

/** IA4-07/ROR-05: per-view relationship style payload for the versioned style.set command. */
export interface ViewStyleUpdate extends ViewStyleValues {
  viewId: string;
  targetId: string;
}

/**
 * Merges a style change over the row read from the model, keeping viewId/targetId
 * authoritative. When the merged routing leaves ORTHOGONAL the adjustment is nulled:
 * the backend rejects a non-orthogonal style.set that carries one (ROR-05), so without
 * this rule the user could not leave orthogonal mode while an adjustment is stored.
 */
export function mergeViewStyleUpdate(
  viewId: string,
  targetId: string,
  current: ViewStyleValues,
  override: Partial<ViewStyleValues>,
): ViewStyleUpdate {
  const merged = { ...current, ...override, viewId, targetId };
  if (merged.routing !== 'ORTHOGONAL') {
    merged.routeAdjustment = null;
  }
  return merged;
}

/**
 * The style that keeps the sides the user connected through: an orthogonal route anchored at the
 * centre of each side, with the auto shape in between (one corridor, no levels).
 */
export function buildAnchorStyleRequest(
  viewId: string,
  relationshipId: string,
  sides: { source: AnchorSide; target: AnchorSide },
): ModelCommandRequest {
  const axis = sides.source === 'N' || sides.source === 'S' ? 'V' : 'H';
  return buildStyleSetRequest(mergeViewStyleUpdate(
    viewId,
    relationshipId,
    { routing: 'ORTHOGONAL', cornerRadius: 0, strokeWidth: 2, dashed: false, routeAdjustment: null },
    {
      routeAdjustment: {
        axis,
        corridors: [0],
        levels: [],
        anchors: { source: { side: sides.source, offset: 0 }, target: { side: sides.target, offset: 0 } },
      },
    },
  ));
}

export function buildStyleSetRequest(payload: ViewStyleUpdate): ModelCommandRequest {
  return { type: 'style.set', payload: { ...payload } };
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
    (count, relationship) => count + 1 + relationship.ends.length + relationship.constraints.length,
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

function payloadNumber(envelope: OperationEnvelope, key: string): number | null {
  const value = envelope.payload[key];
  return typeof value === 'number' ? value : null;
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

function ownerClassifierId(model: ModelResource, scope: 'attribute' | 'operation', elementId: string): string | null {
  const owner = model.classifiers.find((classifier) => {
    if (scope === 'attribute') {
      return classifier.attributes.some((attribute) => attribute.id === elementId);
    }
    return classifier.operations.some((operation) => operation.id === elementId);
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

function updateConstraint(
  model: ModelResource,
  constraintId: string,
  update: (constraint: ConstraintResource) => ConstraintResource,
): ModelResource {
  return {
    ...model,
    classifiers: model.classifiers.map((classifier) => ({
      ...classifier,
      constraints: classifier.constraints.map((constraint) =>
        constraint.id === constraintId ? update(constraint) : constraint),
    })),
    relationships: model.relationships.map((relationship) => ({
      ...relationship,
      constraints: relationship.constraints.map((constraint) =>
        constraint.id === constraintId ? update(constraint) : constraint),
    })),
  };
}

function constraintSeverity(value: string | null): ConstraintSeverity {
  return value === 'INFO' || value === 'WARNING' || value === 'ERROR' ? value : 'ERROR';
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

function parameterOwnerClassifierId(model: ModelResource, parameterId: string): string | null {
  return model.classifiers.find((classifier) => classifier.operations.some((operation) =>
    operation.parameters.some((parameter) => parameter.id === parameterId)))?.id ?? null;
}

function addParameter(model: ModelResource, envelope: OperationEnvelope, classifierId: string): ModelResource {
  const operationId = targetId(envelope, 'operation');
  const id = payloadString(envelope, 'id');
  const name = payloadString(envelope, 'name');
  if (!operationId || !id || !name) {
    return model;
  }
  return updateClassifier(model, classifierId, (classifier) => ({
    ...classifier,
    operations: classifier.operations.map((operation) => {
      if (operation.id !== operationId) {
        return operation;
      }
      const parameter: ParameterResource = {
        id,
        name,
        direction: payloadString(envelope, 'direction') ?? 'IN',
        typeReferenceId: null,
        type: resolveTypeName(model, envelope.payload.type),
        ordinal: typeof envelope.payload.ordinal === 'number' ? envelope.payload.ordinal : operation.parameters.length,
      };
      return { ...operation, parameters: [...operation.parameters, parameter] };
    }),
  }));
}

function updateParameter(
  model: ModelResource,
  envelope: OperationEnvelope,
  classifierId: string,
  parameterId: string,
): ModelResource {
  const requestedOrdinal = envelope.payload.ordinal;
  return updateClassifier(model, classifierId, (classifier) => ({
    ...classifier,
    operations: classifier.operations.map((operation) => {
      if (!operation.parameters.some((parameter) => parameter.id === parameterId)) {
        return operation;
      }
      let parameters = operation.parameters.map((parameter) => {
        if (parameter.id !== parameterId) {
          return parameter;
        }
        return {
          ...parameter,
          name: payloadString(envelope, 'name') ?? parameter.name,
          direction: payloadString(envelope, 'direction') ?? parameter.direction,
          type: Object.hasOwn(envelope.payload, 'type')
            ? resolveTypeName(model, envelope.payload.type)
            : parameter.type,
        };
      });
      if (typeof requestedOrdinal === 'number'
          && Number.isInteger(requestedOrdinal)
          && requestedOrdinal >= 0
          && requestedOrdinal < parameters.length) {
        parameters = [...parameters].sort((left, right) => left.ordinal - right.ordinal);
        const currentIndex = parameters.findIndex((parameter) => parameter.id === parameterId);
        const [moved] = parameters.splice(currentIndex, 1);
        if (moved) {
          parameters.splice(requestedOrdinal, 0, moved);
          parameters = parameters.map((parameter, ordinal) => ({ ...parameter, ordinal }));
        }
      }
      return { ...operation, parameters };
    }),
  }));
}

function removeParameter(model: ModelResource, classifierId: string, parameterId: string): ModelResource {
  return updateClassifier(model, classifierId, (classifier) => ({
    ...classifier,
    operations: classifier.operations.map((operation) => ({
      ...operation,
      parameters: operation.parameters.filter((parameter) => parameter.id !== parameterId),
    })),
  }));
}

export function applyOptimisticCommand(model: ModelResource, envelope: OperationEnvelope): ModelResource {
  const classifierId = targetId(envelope, 'classifier');

  switch (envelope.type) {
    case 'relationship.update': {
      const relationshipId = targetId(envelope, 'relationship');
      if (!relationshipId) {
        return model;
      }
      const kind = payloadString(envelope, 'kind');
      const name = Object.hasOwn(envelope.payload, 'name') ? payloadString(envelope, 'name') : undefined;
      // Mirrors the server: an explicit stereotype wins, and leaving the dependency kind drops it.
      const stereotype = Object.hasOwn(envelope.payload, 'stereotype')
        ? payloadString(envelope, 'stereotype')
        : kind && kind !== 'DEPENDENCY' ? null : undefined;
      const source = envelope.payload.source as Record<string, unknown> | undefined;
      const target = envelope.payload.target as Record<string, unknown> | undefined;
      return {
        ...model,
        relationships: model.relationships.map((relationship) => {
          if (relationship.id !== relationshipId) {
            return relationship;
          }
          return {
            ...relationship,
            ...(kind ? { kind } : {}),
            ...(name !== undefined ? { name } : {}),
            ...(stereotype !== undefined ? { stereotype } : {}),
            ends: relationship.ends.map((end) => {
              const update = end.ordinal === 1 ? source : end.ordinal === 2 ? target : undefined;
              if (!update) {
                return end;
              }
              return {
                ...end,
                ...(typeof update.role === 'string' || update.role === null ? { role: update.role } : {}),
                ...(typeof update.multiplicity === 'string' ? { multiplicity: update.multiplicity } : {}),
                ...(typeof update.navigability === 'string' ? { navigability: update.navigability } : {}),
              };
            }),
          };
        }),
      };
    }
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
    case 'classifier.setStore': {
      if (!classifierId || !Object.hasOwn(envelope.payload, 'store')) {
        return model;
      }
      const store = envelope.payload.store;
      if (store !== null && store !== 'SQL' && store !== 'DOCUMENT') {
        return model;
      }
      // The effective store of a class with no override depends on its relationships and attributes; the
      // canonical model that follows every command carries it, so only a fixed choice is projected here.
      return updateClassifier(model, classifierId, ({ storeOverride: _override, store: _store, storeReason: _reason, ...rest }) => (
        store === null
          ? rest
          : { ...rest, storeOverride: store, store, storeReason: 'Set manually.' }
      ));
    }
    case 'classifier.setNamespace': {
      if (!classifierId || !Object.hasOwn(envelope.payload, 'namespaceId')) {
        return model;
      }
      const namespaceId = envelope.payload.namespaceId;
      if (namespaceId !== null && typeof namespaceId !== 'string') {
        return model;
      }
      return updateClassifier(model, classifierId, (classifier) => ({ ...classifier, namespaceId }));
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
    case 'parameter.add': {
      const operationId = targetId(envelope, 'operation');
      const ownerId = operationId ? ownerClassifierId(model, 'operation', operationId) : null;
      return operationId && ownerId ? addParameter(model, envelope, ownerId) : model;
    }
    case 'parameter.update': {
      const parameterId = targetId(envelope, 'parameter');
      const ownerId = parameterId ? parameterOwnerClassifierId(model, parameterId) : null;
      return parameterId && ownerId ? updateParameter(model, envelope, ownerId, parameterId) : model;
    }
    case 'parameter.remove': {
      const parameterId = targetId(envelope, 'parameter');
      const ownerId = parameterId ? parameterOwnerClassifierId(model, parameterId) : null;
      return parameterId && ownerId ? removeParameter(model, ownerId, parameterId) : model;
    }
    case 'constraint.add': {
      const id = payloadString(envelope, 'id');
      const ownerId = payloadString(envelope, 'ownerId');
      const ownerType = payloadString(envelope, 'ownerType');
      const expression = payloadString(envelope, 'expression');
      if (!id || !ownerId || !expression) {
        return model;
      }
      const constraint: ConstraintResource = {
        id,
        expression,
        severity: constraintSeverity(payloadString(envelope, 'severity')),
      };
      if (ownerType === 'RELATIONSHIP') {
        return {
          ...model,
          relationships: model.relationships.map((relationship) => relationship.id === ownerId
            ? { ...relationship, constraints: [...relationship.constraints, constraint] }
            : relationship),
        };
      }
      if (ownerType === 'CLASSIFIER') {
        return updateClassifier(model, ownerId, (classifier) => ({
          ...classifier,
          constraints: [...classifier.constraints, constraint],
        }));
      }
      return model;
    }
    case 'constraint.update': {
      const constraintId = targetId(envelope, 'constraint');
      if (!constraintId) {
        return model;
      }
      const expression = payloadString(envelope, 'expression');
      const severityValue = payloadString(envelope, 'severity');
      return updateConstraint(model, constraintId, (constraint) => ({
        ...constraint,
        ...(expression !== null ? { expression } : {}),
        ...(severityValue !== null ? { severity: constraintSeverity(severityValue) } : {}),
      }));
    }
    case 'constraint.remove': {
      const constraintId = targetId(envelope, 'constraint');
      if (!constraintId) {
        return model;
      }
      return {
        ...model,
        classifiers: model.classifiers.map((classifier) => ({
          ...classifier,
          constraints: classifier.constraints.filter((constraint) => constraint.id !== constraintId),
        })),
        relationships: model.relationships.map((relationship) => ({
          ...relationship,
          constraints: relationship.constraints.filter((constraint) => constraint.id !== constraintId),
        })),
      };
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
    case 'operation.remove': {
      const scope = envelope.type.split('.')[0] ?? '';
      const removedId = targetId(envelope, scope);
      if (!removedId || (scope !== 'attribute' && scope !== 'operation')) {
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
        return { ...classifier, operations: classifier.operations.filter((operation) => operation.id !== removedId) };
      });
    }
    case 'style.set': {
      // Server upsert semantics (StyleSetHandler): one view_style row per (viewId, targetId),
      // applied locally so the optimistic model already carries the adjustment and the next
      // model sync cannot rewind a just-dragged route back to the auto geometry.
      const viewId = payloadString(envelope, 'viewId');
      const styleTargetId = payloadString(envelope, 'targetId');
      if (!viewId || !styleTargetId) {
        return model;
      }
      const routing = payloadString(envelope, 'routing');
      const styleRow: ViewStyleResource = {
        viewId,
        targetId: styleTargetId,
        routing: routing === 'FREE' || routing === 'ORTHOGONAL' ? routing : 'STRAIGHT',
        cornerRadius: payloadNumber(envelope, 'cornerRadius') ?? 0,
        strokeWidth: payloadNumber(envelope, 'strokeWidth') ?? 2,
        dashed: payloadBoolean(envelope, 'dashed') ?? false,
        routeAdjustment: envelope.payload.routeAdjustment
          ? (envelope.payload.routeAdjustment as RouteAdjustmentResource)
          : null,
      };
      const existingRow = (model.view_style ?? []).find((candidate) =>
        candidate.viewId === viewId && candidate.targetId === styleTargetId);
      return {
        ...model,
        view_style: existingRow
          ? (model.view_style ?? []).map((candidate) => (candidate === existingRow ? styleRow : candidate))
          : [...(model.view_style ?? []), styleRow],
      };
    }
    default:
      return model;
  }
}
