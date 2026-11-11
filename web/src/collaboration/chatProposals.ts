import { ApiTransportError } from '../api/client';
import type { ChatProposalResource, ChatProposalStatus, Uuid } from '../api/types';
import { PROVIDER_DISABLED_MESSAGE } from '../assistant/chatAskFailure';

const STATUSES: readonly ChatProposalStatus[] = ['PENDING', 'APPLYING', 'ACCEPTED', 'REJECTED', 'FAILED'];

function objectValue(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? value as Record<string, unknown> : null;
}

/** Reads a proposal from a versioned project-event envelope without trusting the payload shape. */
export function proposalFromProjectEvent(data: unknown): ChatProposalResource | null {
  const payload = objectValue(objectValue(data)?.payload);
  const proposal = objectValue(payload?.proposal);
  if (typeof proposal?.id !== 'string'
    || typeof proposal.messageId !== 'string'
    || typeof proposal.sessionId !== 'string'
    || typeof proposal.status !== 'string'
    || !STATUSES.includes(proposal.status as ChatProposalStatus)
    || !Array.isArray(proposal.operations)) {
    return null;
  }
  return proposal as unknown as ChatProposalResource;
}

/** Replaces the proposal with the same id or appends it; events and HTTP responses may arrive in any order. */
export function upsertProposal(
  current: ChatProposalResource[],
  proposal: ChatProposalResource,
  activeSessionId: Uuid | null,
): ChatProposalResource[] {
  const sessionProposals = current.filter((entry) => entry.sessionId === activeSessionId);
  if (!activeSessionId || proposal.sessionId !== activeSessionId) {
    return sessionProposals;
  }
  return sessionProposals.some((entry) => entry.id === proposal.id)
    ? sessionProposals.map((entry) => entry.id === proposal.id ? proposal : entry)
    : [...sessionProposals, proposal];
}

export function proposalStatusLabel(status: ChatProposalStatus): string {
  switch (status) {
    case 'PENDING': return 'En espera de una persona editora';
    case 'APPLYING': return 'Aplicando';
    case 'ACCEPTED': return 'Aplicado';
    case 'REJECTED': return 'Rechazado';
    case 'FAILED': return 'No se pudo aplicar';
  }
}

/** A user-facing reason for a failed suggest, accept or reject call. */
export function proposalErrorMessage(error: unknown, fallback: (cause: unknown) => string): string {
  if (error instanceof ApiTransportError) {
    if (error.code === 'PROVIDER_DISABLED') {
      return PROVIDER_DISABLED_MESSAGE;
    }
    if (error.code === 'NO_PROPOSAL') {
      return error.message;
    }
    if (error.code === 'NO_IMAGE') {
      return 'Ese mensaje no tiene una foto para convertir en sugerencia.';
    }
    if (error.code === 'PROPOSAL_DECIDED') {
      return 'Otra persona editora ya decidió sobre esta sugerencia.';
    }
  }
  return fallback(error);
}
