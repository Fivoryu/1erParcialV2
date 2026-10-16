import { describe, expect, it } from 'vitest';
import type { ChatMessageResource } from '../api/types';
import {
  appendMessageToSession,
  attachmentIdsFromHistory,
  chatAiStatusFromProjectEvent,
  limitChatAttachments,
  mergeSessionHistory,
  messageFromProjectEvent,
  updateSessionAiStatus,
} from './chatState';

const sessionId = '11111111-1111-4111-8111-111111111111';
const otherSessionId = '22222222-2222-4222-8222-222222222222';
const question: ChatMessageResource = {
  id: '33333333-3333-4333-8333-333333333333',
  sessionId,
  kind: 'HUMAN',
  authorUserId: '44444444-4444-4444-8444-444444444444',
  authorDisplayName: 'Riley',
  body: 'Explain this image',
  createdAt: '2026-02-01T15:00:00Z',
  replyTo: null,
  attachments: ['55555555-5555-4555-8555-555555555555'],
};
const answer: ChatMessageResource = {
  ...question,
  id: '66666666-6666-4666-8666-666666666666',
  kind: 'AI',
  authorUserId: null,
  authorDisplayName: 'Assistant',
  body: 'The image contains a class diagram.',
  replyTo: question.id,
  attachments: [],
};

describe('shared chat state', () => {
  it('parses a posted assistant message including its session, reply link, and attachments', () => {
    expect(messageFromProjectEvent({ payload: { message: answer } })).toEqual(answer);
    expect(messageFromProjectEvent({ payload: { message: { ...answer, sessionId: undefined } } })).toBeNull();
  });

  it('adds only messages for the active session and deduplicates POST/SSE delivery', () => {
    const history = [question];
    expect(appendMessageToSession(history, answer, otherSessionId)).toEqual([]);
    expect(appendMessageToSession(history, question, sessionId)).toBe(history);
    expect(appendMessageToSession(history, answer, sessionId)).toEqual([question, answer]);
    const stale = { ...question, id: otherSessionId, sessionId: otherSessionId };
    expect(appendMessageToSession([stale], answer, sessionId)).toEqual([answer]);
  });

  it('merges session history without retaining stale-session messages', () => {
    const stale = { ...question, id: otherSessionId, sessionId: otherSessionId };
    expect(mergeSessionHistory([stale], [question, answer, stale], sessionId)).toEqual([question, answer]);
  });

  it('tracks actual AI request states per session and ignores malformed statuses', () => {
    const queued = chatAiStatusFromProjectEvent({
      payload: { sessionId, state: 'QUEUED' },
    });
    const failed = chatAiStatusFromProjectEvent({
      payload: { sessionId: otherSessionId, state: 'FAILED', reason: 'Provider unavailable' },
    });
    expect(queued).toEqual({ sessionId, state: 'QUEUED' });
    expect(failed).toEqual({ sessionId: otherSessionId, state: 'FAILED', reason: 'Provider unavailable' });
    expect(chatAiStatusFromProjectEvent({ payload: { sessionId, state: 'THINKING' } })).toBeNull();

    const statuses = updateSessionAiStatus({}, queued!);
    expect(updateSessionAiStatus(statuses, failed!)).toEqual({
      [sessionId]: queued,
      [otherSessionId]: failed,
    });
  });

  it('collects distinct attachment ids to load bytes for thumbnails', () => {
    expect(attachmentIdsFromHistory([question, answer, { ...question, id: otherSessionId }]))
      .toEqual(['55555555-5555-4555-8555-555555555555']);
  });

  it('limits a message to three images and reports any excess', () => {
    const images = ['one.png', 'two.png', 'three.png', 'four.png']
      .map((name) => ({ name }) as unknown as File);
    const selection = limitChatAttachments(images);

    expect(selection.selected).toEqual(images.slice(0, 3));
    expect(selection.rejectedCount).toBe(1);
  });
});
