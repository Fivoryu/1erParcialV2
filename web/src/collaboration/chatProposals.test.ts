import { describe, expect, it } from 'vitest';
import { ApiTransportError } from '../api/client';
import type { ChatProposalResource } from '../api/types';
import { PROVIDER_DISABLED_MESSAGE } from '../assistant/chatAskFailure';
import { proposalErrorMessage, proposalFromProjectEvent, proposalStatusLabel, upsertProposal } from './chatProposals';

const sessionId = '77777777-7777-4777-8777-777777777777';

function proposal(overrides: Partial<ChatProposalResource> = {}): ChatProposalResource {
  return {
    id: '11111111-1111-4111-8111-111111111111',
    messageId: '22222222-2222-4222-8222-222222222222',
    sessionId,
    requestedByUserId: '33333333-3333-4333-8333-333333333333',
    requestedByDisplayName: 'Morgan Reader',
    utterance: 'Add a Patient class',
    operations: [{ type: 'classifier.create', targetId: null, args: { name: 'Patient' } }],
    baseRevision: 4,
    status: 'PENDING',
    decidedByUserId: null,
    decidedByDisplayName: null,
    decidedAt: null,
    resultSummary: null,
    createdAt: '2026-02-01T15:10:00Z',
    ...overrides,
  };
}

describe('proposalFromProjectEvent', () => {
  it('reads the proposal out of a project event envelope', () => {
    const created = proposal();
    expect(proposalFromProjectEvent({ payload: { proposal: created, sessionId } })).toEqual(created);
  });

  it('rejects payloads that are not a proposal', () => {
    expect(proposalFromProjectEvent(null)).toBeNull();
    expect(proposalFromProjectEvent({ payload: { proposal: { id: 'x' } } })).toBeNull();
    expect(proposalFromProjectEvent({ payload: { proposal: { ...proposal(), status: 'DONE' } } })).toBeNull();
    expect(proposalFromProjectEvent({ payload: { proposal: { ...proposal(), operations: 'nope' } } })).toBeNull();
  });
});

describe('upsertProposal', () => {
  it('appends a new proposal and replaces a known one in place', () => {
    const first = proposal();
    const second = proposal({ id: '44444444-4444-4444-8444-444444444444' });
    const withBoth = upsertProposal(upsertProposal([], first, sessionId), second, sessionId);
    expect(withBoth.map((entry) => entry.id)).toEqual([first.id, second.id]);

    const decided = proposal({ status: 'ACCEPTED', resultSummary: '1 of 1 change applied.' });
    const updated = upsertProposal(withBoth, decided, sessionId);
    expect(updated).toHaveLength(2);
    expect(updated[0]?.status).toBe('ACCEPTED');
  });

  it('ignores proposals of another session and drops the ones left from a previous session', () => {
    const other = proposal({ sessionId: '99999999-9999-4999-8999-999999999999' });
    expect(upsertProposal([proposal()], other, sessionId)).toHaveLength(1);
    expect(upsertProposal([proposal()], proposal(), '99999999-9999-4999-8999-999999999999')).toEqual([]);
    expect(upsertProposal([], proposal(), null)).toEqual([]);
  });
});

describe('proposalErrorMessage', () => {
  const fallback = () => 'fallback';

  it('names the real reason instead of a generic connection error', () => {
    const disabled = new ApiTransportError('PROVIDER_DISABLED', 'off', [], null, 503);
    const none = new ApiTransportError('NO_PROPOSAL', 'Which class do you mean?', [], null, 422);
    const decided = new ApiTransportError('PROPOSAL_DECIDED', 'decided', [], null, 409);
    expect(proposalErrorMessage(disabled, fallback)).toBe(PROVIDER_DISABLED_MESSAGE);
    expect(proposalErrorMessage(none, fallback)).toBe('Which class do you mean?');
    expect(proposalErrorMessage(decided, fallback)).toBe('Another editor already decided this suggestion.');
    expect(proposalErrorMessage(new Error('boom'), fallback)).toBe('fallback');
  });
});

describe('proposalStatusLabel', () => {
  it('has a label for every status', () => {
    expect(proposalStatusLabel('PENDING')).toBe('Waiting for an editor');
    expect(proposalStatusLabel('ACCEPTED')).toBe('Applied');
    expect(proposalStatusLabel('REJECTED')).toBe('Rejected');
    expect(proposalStatusLabel('FAILED')).toBe('Could not be applied');
    expect(proposalStatusLabel('APPLYING')).toBe('Being applied');
  });
});
