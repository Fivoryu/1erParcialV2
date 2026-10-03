import { describe, expect, it } from 'vitest';
import {
  decideEditingLeaseHeartbeat,
  deriveEditingLeaseState,
  mapEditingLeaseConflict,
} from './editingLease';

const userId = '11111111-1111-4111-8111-111111111111';
const otherUserId = '22222222-2222-4222-8222-222222222222';
const targetId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const now = Date.parse('2026-10-03T12:00:00.000Z');
const liveLease = {
  modelId: '33333333-3333-4333-8333-333333333333',
  editorUserId: otherUserId,
  editorDisplayName: 'Alex Rivera',
  targetType: 'CLASSIFIER',
  targetId,
  acquiredAt: '2026-10-03T11:59:30.000Z',
  expiresAt: '2026-10-03T12:00:30.000Z',
};

const conflict = {
  editorDisplayName: 'Alex Rivera',
  targetType: 'CLASSIFIER',
  targetId,
  reclaimable: false,
  retryAfterSeconds: 30,
};

describe('editing lease decisions', () => {
  it('maps the 409 details carrier and generation outcome carrier to the same canonical conflict', () => {
    const fromError = mapEditingLeaseConflict({
      code: 'EXPORT_BLOCKED_EDITING',
      details: [conflict],
    });
    const fromOutcome = mapEditingLeaseConflict({
      code: 'EXPORT_BLOCKED_EDITING',
      result: conflict,
    });

    expect(fromError).toEqual(conflict);
    expect(fromOutcome).toEqual(conflict);
    expect(fromOutcome).toEqual(fromError);
  });

  it('rejects malformed 409 and outcome carriers', () => {
    expect(mapEditingLeaseConflict({ code: 'PRECONDITION_FAILED', details: [conflict] })).toBeNull();
    expect(mapEditingLeaseConflict({ code: 'PRECONDITION_FAILED', result: conflict })).toBeNull();
    expect(mapEditingLeaseConflict(null)).toBeNull();
    expect(mapEditingLeaseConflict(['not a carrier'])).toBeNull();
    expect(mapEditingLeaseConflict({ code: 'EXPORT_BLOCKED_EDITING' })).toBeNull();
    expect(mapEditingLeaseConflict({ code: 'EXPORT_BLOCKED_EDITING', details: {} })).toBeNull();
  });

  it('rejects every invalid conflict field', () => {
    const invalidCandidates = [
      { ...conflict, editorDisplayName: 42 },
      { ...conflict, reclaimable: 'yes' },
      { ...conflict, retryAfterSeconds: -1 },
      { ...conflict, retryAfterSeconds: 1.5 },
      { ...conflict, retryAfterSeconds: Number.NaN },
      { ...conflict, retryAfterSeconds: '30' },
    ];

    for (const candidate of invalidCandidates) {
      expect(mapEditingLeaseConflict({
        code: 'EXPORT_BLOCKED_EDITING',
        details: [candidate],
      })).toBeNull();
    }
  });

  it('derives no lease, ours, foreign-live, and foreign-expired states at a fixed instant', () => {
    expect(deriveEditingLeaseState(null, userId, now).kind).toBe('none');
    expect(deriveEditingLeaseState({ ...liveLease, editorUserId: userId }, userId, now).kind).toBe('ours');
    expect(deriveEditingLeaseState(liveLease, userId, now).kind).toBe('foreign-live');
    expect(deriveEditingLeaseState({ ...liveLease, expiresAt: '2026-10-03T11:59:59.999Z' }, userId, now).kind)
      .toBe('foreign-expired');
  });

  it('renews only a live lease that is ours and does nothing for foreign or missing leases', () => {
    const ours = deriveEditingLeaseState({ ...liveLease, editorUserId: userId }, userId, now);
    const foreign = deriveEditingLeaseState(liveLease, userId, now);
    const none = deriveEditingLeaseState(null, userId, now);

    expect(decideEditingLeaseHeartbeat(ours, now + 14_999)).toBe('none');
    expect(decideEditingLeaseHeartbeat(ours, now + 15_000)).toBe('renew');
    expect(decideEditingLeaseHeartbeat(foreign, now + 15_000)).toBe('none');
    expect(decideEditingLeaseHeartbeat(none, now + 15_000)).toBe('none');
  });
});
