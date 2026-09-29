import type {
  CommandAuthor,
  CommandOrigin,
  CommandPrecondition,
  CommandTarget,
  OperationEnvelope,
  Uuid,
} from './types';

export const DEFAULT_COMMAND_AUTHOR: CommandAuthor = {
  userId: '00000000-0000-0000-0000-000000000001',
  externalSubject: 'seed-owner-local',
};

export interface BuildOperationEnvelopeInput {
  type: string;
  projectId: Uuid;
  modelId: Uuid;
  baseRevision: number;
  author?: CommandAuthor;
  targets?: CommandTarget[];
  preconditions?: CommandPrecondition[];
  payload?: Record<string, unknown>;
  origin?: CommandOrigin;
  causalParentOperationId?: Uuid | null;
  idempotencyKey?: string;
}

export type UuidFactory = () => Uuid;

function browserUuid(): Uuid {
  return globalThis.crypto.randomUUID();
}

/** Build the complete canonical command envelope and client-owned create/add ID in one place. */
export function buildOperationEnvelope(
  input: BuildOperationEnvelopeInput,
  uuidFactory: UuidFactory = browserUuid,
): OperationEnvelope {
  const operationId = uuidFactory();
  const createsElement = /\.(create|add)$/.test(input.type);
  const payload = { ...(input.payload ?? {}) };

  if (createsElement) {
    payload.id = uuidFactory();
  }

  return {
    operationId,
    type: input.type,
    author: { ...(input.author ?? DEFAULT_COMMAND_AUTHOR) },
    projectId: input.projectId,
    modelId: input.modelId,
    baseRevision: input.baseRevision,
    targets: [...(input.targets ?? [])],
    preconditions: [...(input.preconditions ?? [])],
    payload,
    origin: input.origin ?? 'MANUAL',
    causalParentOperationId: input.causalParentOperationId ?? null,
    idempotencyKey: input.idempotencyKey ?? uuidFactory(),
  };
}
