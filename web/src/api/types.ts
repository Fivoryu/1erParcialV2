export type Uuid = string;

export interface ProjectResource {
  id: Uuid;
  name: string;
  description: string | null;
}

export type MemberRole = 'OWNER' | 'EDITOR' | 'VIEWER';
export type AssignableRole = Exclude<MemberRole, 'OWNER'>;
export type MemberStatus = 'ACTIVE' | 'REMOVED';
export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED';

export interface MemberResource {
  userId: Uuid;
  username: string;
  displayName: string;
  role: MemberRole;
  status: MemberStatus;
}

export interface InvitationResource {
  projectId: Uuid;
  projectName: string;
  id: Uuid;
  inviteeUsername: string | null;
  inviteeDisplayName: string | null;
  role: MemberRole;
  status: InvitationStatus;
  invitedByUsername: string;
  createdAt: string;
}

export interface CreateInvitationRequest {
  username: string;
  role: AssignableRole;
}

export interface UpdateMemberRoleRequest {
  role: AssignableRole;
}

export interface UserResource {
  id: Uuid;
  username: string;
  displayName: string;
  email: string | null;
}

export interface RegisterRequest {
  username: string;
  password: string;
  displayName: string;
  email?: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  expiresAt: string;
  user: UserResource;
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
  versionId: Uuid;
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

export type ConstraintSeverity = 'INFO' | 'WARNING' | 'ERROR';

export interface ConstraintResource {
  id: Uuid;
  expression: string;
  severity: ConstraintSeverity;
}

export interface RelationshipResource {
  id: Uuid;
  kind: string;
  name: string | null;
  ends: RelationshipEndResource[];
  constraints: ConstraintResource[];
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

export type GenerationOutcome = 'ACCEPTED' | 'REJECTED';
export type GenerationState = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
export type GenerationSeverity = 'INFO' | 'WARNING' | 'ERROR';
export type VerificationState = 'PASSED' | 'FAILED' | 'NOT_RUN';

/** A stable diagnostic produced while mapping a frozen generation snapshot. */
export interface GenerationDiagnosticResource {
  code: string;
  severity: GenerationSeverity;
  elementId: Uuid | null;
  feature: string;
  message: string;
  blocking: boolean;
}

export interface GenerationTargetResource {
  targetId: Uuid;
  modelId: Uuid;
  targetKey: string;
  name: string;
  databaseEngine: string | null;
  databaseName: string;
  databaseSchema: string | null;
  connectionConfigRef: string | null;
  configurationRevision: number;
  createdAt: string;
  updatedAt: string;
}

export interface GenerationProfileResource {
  profileId: Uuid;
  profileKey: string;
  name: string;
  profileRevision: number;
  templateId: Uuid;
  templateRevision: number;
}

/** The destination metadata a client may author. Secrets never travel here. */
export interface CreateGenerationTargetRequest {
  targetKey: string;
  name: string;
  databaseEngine: string;
  databaseName: string;
  databaseSchema: string | null;
  connectionConfigRef: string | null;
}

/** The exact identities a generation run freezes. No default hides one. */
export interface CreateGenerationRequest {
  modelId: Uuid;
  versionId: Uuid;
  targetId: Uuid;
  profileId: Uuid;
  templateId: Uuid;
  idempotencyKey: string;
}

export interface VerificationCheckResource {
  verificationId: Uuid;
  checkName: string;
  state: VerificationState;
  evidence: string;
  evidenceHash: string;
  toolName: string;
}

/** What per-run verification recorded, including the checks it did not run. */
export interface VerificationSummaryResource {
  runId: Uuid;
  generationState: GenerationState;
  status: VerificationState;
  verifiedBackend: boolean;
  checks: VerificationCheckResource[];
}

export interface GenerationRunResource {
  runId: Uuid;
  modelId: Uuid;
  versionId: Uuid;
  modelRevision: number;
  targetId: Uuid;
  targetKey: string;
  targetConfigRevision: number;
  profileId: Uuid;
  profileKey: string;
  profileRevision: number;
  templateId: Uuid;
  templateRevision: number;
  state: GenerationState;
  inputSnapshotHash: string | null;
  artifactSetHash: string | null;
  artifactZipHash: string | null;
  artifactZipUri: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  diagnostics: GenerationDiagnosticResource[];
  verification: VerificationSummaryResource | null;
}

/** The uniform operation envelope the generation endpoints answer with. */
export interface OperationOutcomeResource<TResult> {
  outcome: GenerationOutcome;
  code: string;
  message: string;
  idempotencyKey: string | null;
  result: TResult | null;
}

export type GenerationArchiveDownload =
  | { kind: 'archive'; filename: string; blob: Blob }
  | { kind: 'rejected'; code: string; message: string };

export type DeploymentState = 'PENDING' | 'RUNNING' | 'UP' | 'FAILED' | 'STOPPING' | 'STOPPED';

/** One generated backend running on the user's machine. */
export interface DeploymentResource {
  deploymentId: Uuid;
  state: DeploymentState;
  targetId: Uuid;
  targetKey: string;
  targetName: string;
  runId: Uuid;
  httpHostPort: number;
  postgresHostPort: number;
  composeProject: string | null;
  operatorId: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  requestedAt: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}
