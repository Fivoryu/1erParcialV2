export type Uuid = string;

export interface ProjectResource {
  id: Uuid;
  name: string;
  description: string | null;
}

export interface CreateProjectRequest {
  name: string;
  description: string | null;
}

export interface ApiErrorResource {
  code: string;
  message: string;
  details: Array<Record<string, unknown>>;
  operationId: Uuid | null;
}

export interface ModelResource {
  id: Uuid;
  projectId: Uuid;
  revision: number;
  views: ViewResource[];
  classifiers: ClassifierResource[];
  relationships: RelationshipResource[];
}

export interface ViewResource {
  id: Uuid;
  name: string;
  is_default: boolean;
  element_geometry: GeometryResource[];
  connector_path: ConnectorPathResource[];
}

export interface GeometryResource {
  id: Uuid;
  targetType: string;
  targetId: Uuid;
  x: number;
  y: number;
  w: number;
  h: number;
  zOrder: number;
}

export interface ConnectorPathResource {
  id: Uuid;
  relationshipId: Uuid;
  ordinal: number;
  x: number;
  y: number;
}

export interface ClassifierResource {
  id: Uuid;
  name: string;
  kind: string;
  isAbstract: boolean;
  namespaceId: Uuid | null;
  attributes: AttributeResource[];
  operations: OperationResource[];
  constraints: ConstraintResource[];
}

export interface AttributeResource {
  id: Uuid;
  name: string;
  kind: string;
  visibility: string;
  multiplicity: string;
  defaultValue: string | null;
  typeReferenceId: Uuid | null;
  type: string | null;
  ordinal: number;
}

export interface OperationResource {
  id: Uuid;
  name: string;
  visibility: string;
  isAbstract: boolean;
  isStatic: boolean;
  returnTypeReferenceId: Uuid | null;
  returnType: string | null;
  ordinal: number;
  parameters: ParameterResource[];
}

export interface ParameterResource {
  id: Uuid;
  name: string;
  direction: string;
  typeReferenceId: Uuid | null;
  type: string | null;
  ordinal: number;
}

export interface ConstraintResource {
  id: Uuid;
  expression: string;
  severity: string;
}

export interface RelationshipResource {
  id: Uuid;
  kind: string;
  name: string | null;
  ends: RelationshipEndResource[];
}

export interface RelationshipEndResource {
  id: Uuid;
  classifierId: Uuid;
  role: string | null;
  multiplicity: string;
  navigability: string;
  aggregationKind: string;
  ordinal: number;
}

export type ErrorCode =
  | 'UNKNOWN_OPERATION'
  | 'INVALID_PAYLOAD'
  | 'PRECONDITION_FAILED'
  | 'REVISION_MISMATCH'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'NAME_COLLISION_BLOCKS'
  | 'MODEL_INVALID';

export interface CommandTarget {
  scope: string;
  id: Uuid;
}

export interface CommandPrecondition {
  kind: string;
  expect: unknown;
  scope: unknown | null;
}

export type CommandOrigin =
  | 'MANUAL'
  | 'AI'
  | 'VOICE'
  | 'PHOTO'
  | 'XMI'
  | 'UNDO'
  | 'REDO'
  | 'RESOLUTION'
  | 'ADMIN'
  | 'OFFLINE'
  | 'IMPORT';

export interface CommandAuthor {
  userId: Uuid;
  externalSubject: string;
}

export interface OperationEnvelope {
  operationId: Uuid;
  type: string;
  author: CommandAuthor;
  projectId: Uuid;
  modelId: Uuid;
  baseRevision: number;
  targets: CommandTarget[];
  preconditions: CommandPrecondition[];
  payload: Record<string, unknown>;
  origin: CommandOrigin;
  causalParentOperationId: Uuid | null;
  idempotencyKey: string;
}

export interface CommandDiagnostic {
  code: ErrorCode;
  message: string;
  scope: string | null;
  id: Uuid | null;
  reason: string | null;
}

export type CommandStatus = 'ACCEPTED' | 'REJECTED' | 'CONFLICT' | 'DEFERRED';

export interface CommandResult {
  operationId: Uuid;
  status: CommandStatus;
  resultRevision: number | null;
  diagnostics: CommandDiagnostic[];
  authorizationClassification: string | null;
}

export type CommandOutcome =
  | { kind: 'accepted'; result: CommandResult }
  | { kind: 'rejected'; result: CommandResult; diagnostics: CommandDiagnostic[] }
  | { kind: 'conflict'; result: CommandResult }
  | { kind: 'deferred'; result: CommandResult };
