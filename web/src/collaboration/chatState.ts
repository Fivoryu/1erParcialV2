import type { ChatMessageResource, Uuid } from '../api/types';

export type ChatAiState = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface SessionAiStatus {
  sessionId: Uuid;
  state: ChatAiState;
  reason?: string;
}

function objectValue(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? value as Record<string, unknown> : null;
}

/** Reads the versioned project-event envelope without trusting or rendering arbitrary payloads. */
export function messageFromProjectEvent(data: unknown): ChatMessageResource | null {
  const envelope = objectValue(data);
  const payload = objectValue(envelope?.payload);
  const message = objectValue(payload?.message);
  const authorUserId = message?.authorUserId;
  const kind = message?.kind;
  const attachments = message?.attachments;
  if (typeof message?.id !== 'string'
    || typeof message.sessionId !== 'string'
    || (kind !== 'HUMAN' && kind !== 'AI')
    || (authorUserId !== null && typeof authorUserId !== 'string')
    || typeof message.authorDisplayName !== 'string'
    || typeof message.body !== 'string'
    || typeof message.createdAt !== 'string') {
    return null;
  }

  return {
    id: message.id,
    sessionId: message.sessionId,
    kind,
    authorUserId,
    authorDisplayName: message.authorDisplayName,
    body: message.body,
    createdAt: message.createdAt,
    replyTo: typeof message.replyTo === 'string' ? message.replyTo : null,
    attachments: Array.isArray(attachments) ? attachments.filter((id): id is string => typeof id === 'string') : [],
  };
}

/** Appends one event/history result only to its matching active session and deduplicates SSE + POST. */
export function appendMessageToSession(
  history: ChatMessageResource[],
  message: ChatMessageResource,
  activeSessionId: Uuid | null,
): ChatMessageResource[] {
  const sessionHistory = history.every((entry) => entry.sessionId === activeSessionId)
    ? history
    : history.filter((entry) => entry.sessionId === activeSessionId);
  if (!activeSessionId || message.sessionId !== activeSessionId || sessionHistory.some((entry) => entry.id === message.id)) {
    return sessionHistory;
  }
  return [...sessionHistory, message];
}

export function mergeSessionHistory(
  current: ChatMessageResource[],
  incoming: ChatMessageResource[],
  activeSessionId: Uuid | null,
): ChatMessageResource[] {
  return incoming.reduce(
    (history, message) => appendMessageToSession(history, message, activeSessionId),
    current.filter((message) => message.sessionId === activeSessionId),
  );
}

export function chatAiStatusFromProjectEvent(data: unknown): SessionAiStatus | null {
  const envelope = objectValue(data);
  const payload = objectValue(envelope?.payload);
  const state = payload?.state;
  if (typeof payload?.sessionId !== 'string'
    || (state !== 'QUEUED' && state !== 'RUNNING' && state !== 'COMPLETED'
      && state !== 'FAILED' && state !== 'CANCELLED')) {
    return null;
  }
  return {
    sessionId: payload.sessionId,
    state,
    ...(typeof payload.reason === 'string' ? { reason: payload.reason } : {}),
  };
}

export function updateSessionAiStatus(
  current: Record<Uuid, SessionAiStatus>,
  status: SessionAiStatus,
): Record<Uuid, SessionAiStatus> {
  return { ...current, [status.sessionId]: status };
}

export function attachmentIdsFromHistory(history: ChatMessageResource[]): Uuid[] {
  return [...new Set(history.flatMap((message) => message.attachments ?? []))];
}

export function limitChatAttachments(images: File[]): { selected: File[]; rejectedCount: number } {
  return { selected: images.slice(0, 3), rejectedCount: Math.max(0, images.length - 3) };
}
