import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { EditingLeaseConflict, EditingLeaseResource } from '../api/types';
import { deriveEditingLeaseState } from './editingLease';
import EditingLeaseNotice from './EditingLeaseNotice';

const userId = '11111111-1111-4111-8111-111111111111';
const otherUserId = '22222222-2222-4222-8222-222222222222';
const targetId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const now = Date.parse('2026-10-03T12:00:00.000Z');
const lease = {
  modelId: '33333333-3333-4333-8333-333333333333',
  editorUserId: otherUserId,
  editorDisplayName: 'Alex Rivera',
  targetType: 'CLASSIFIER',
  targetId,
  acquiredAt: '2026-10-03T11:59:30.000Z',
  expiresAt: '2026-10-03T12:00:30.000Z',
};

function conflictForLease(lease: EditingLeaseResource | null, currentUserId: string): EditingLeaseConflict | null {
  const state = deriveEditingLeaseState(lease, currentUserId, now);
  return state.kind === 'foreign-live' || state.kind === 'foreign-expired' ? state.conflict : null;
}

function render(conflict: EditingLeaseConflict | null): string {
  return renderToStaticMarkup(createElement(EditingLeaseNotice, {
    conflict,
    onReclaim: vi.fn(),
    busy: false,
  }));
}

describe('editing lease notice', () => {
  it('renders nothing when there is no lease recorded', () => {
    expect(render(conflictForLease(null, userId))).toBe('');
  });

  it('shows the editor and reason, with reclaim disabled while their lease is live', () => {
    const markup = render(conflictForLease(lease, userId));

    expect(markup).toContain('Alex Rivera');
    expect(markup).toContain('está editando este diagrama.');
    expect(markup).toContain('La exportación estará bloqueada hasta que expire su permiso.');
    expect(markup).toMatch(/<button\b[^>]*disabled(?:="")?[^>]*>Recuperar el control<\/button>/);
  });

  it('enables reclaim when the foreign lease has expired', () => {
    const expired = { ...lease, expiresAt: '2026-10-03T11:59:59.999Z' };
    const markup = render(conflictForLease(expired, userId));

    expect(markup).toContain('Alex Rivera');
    expect(markup).toContain('El permiso de edición ha expirado.');
    expect(markup).toMatch(/<button\b[^>]*>Recuperar el control<\/button>/);
    expect(markup).not.toMatch(/<button\b[^>]*disabled/);
  });

  it('shows no notice for our own lease', () => {
    expect(render(conflictForLease({ ...lease, editorUserId: userId }, userId))).toBe('');
  });
});
