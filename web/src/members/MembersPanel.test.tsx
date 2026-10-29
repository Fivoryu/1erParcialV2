import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ApiTransportError } from '../api/client';
import type { InvitationResource, MemberResource } from '../api/types';
import { loginErrorMessage } from '../pages/LoginPage';
import { MembersPanelView, type MembersPanelViewProps } from './MembersPanel';

const owner: MemberResource = {
  userId: '11111111-1111-4111-8111-111111111111',
  username: 'project.owner',
  displayName: 'Project Owner',
  role: 'OWNER',
  status: 'ACTIVE',
};

const editor: MemberResource = {
  userId: '22222222-2222-4222-8222-222222222222',
  username: 'diagram.user',
  displayName: 'Diagram User',
  role: 'EDITOR',
  status: 'ACTIVE',
};

const pendingInvitation: InvitationResource = {
  projectId: '33333333-3333-4333-8333-333333333333',
  projectName: 'Library system',
  id: '44444444-4444-4444-8444-444444444444',
  inviteeUsername: 'new.member',
  inviteeDisplayName: 'New Member',
  role: 'VIEWER',
  status: 'PENDING',
  invitedByUsername: 'project.owner',
  createdAt: '2026-10-01T12:00:00Z',
};

function props(overrides: Partial<MembersPanelViewProps> = {}): MembersPanelViewProps {
  return {
    userId: editor.userId,
    members: [owner, editor],
    pendingInvitations: [pendingInvitation],
    loadingMembers: false,
    loadingInvitations: false,
    membersError: null,
    invitationsError: null,
    inviteError: null,
    actionError: null,
    inviteUsername: '',
    inviteRole: 'VIEWER',
    inviteBusy: false,
    busyMemberId: null,
    busyInvitationId: null,
    onInviteUsernameChange: vi.fn(),
    onInviteRoleChange: vi.fn(),
    onInvite: vi.fn(),
    onChangeRole: vi.fn(),
    onRemoveMember: vi.fn(),
    onRevokeInvitation: vi.fn(),
    ...overrides,
  };
}

function render(overrides: Partial<MembersPanelViewProps> = {}): string {
  return renderToStaticMarkup(createElement(MembersPanelView, props(overrides)));
}

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

describe('project members panel', () => {
  it('shows the member list to a non-owner without administrative controls', () => {
    const markup = render();

    expect(markup).toContain('Project Owner');
    expect(markup).toContain('Diagram User');
    expect(markup).toContain('Propietario');
    expect(markup).toContain('Editor');
    expect(markup).not.toContain('Invitar a una persona');
    expect(markup).not.toContain('member-invite-form');
    expect(markup).not.toContain('>Quitar</button>');
    expect(markup).not.toContain('>Revocar</button>');
    expect(markup).not.toMatch(UUID_PATTERN);
  });

  it('shows the invite form, pending invitations, and member controls to an owner', () => {
    const markup = render({ userId: owner.userId });

    expect(markup).toContain('Invitar a una persona');
    expect(markup).toContain('member-invite-form');
    expect(markup).toContain('Enviar invitación');
    expect(markup).toContain('New Member');
    expect(markup).toContain('Acceso de Lector');
    expect(markup).toContain('>Revocar</button>');
    expect(markup).toContain('>Quitar</button>');
    expect(markup).not.toMatch(UUID_PATTERN);
    expect(markup).not.toContain('OWNER');
    expect(markup).not.toContain('EDITOR');
    expect(markup).not.toContain('VIEWER');
  });

  it('does not offer an Owner option in the member role selector', () => {
    const markup = render({ userId: owner.userId });

    expect(markup).not.toMatch(/<option value="Owner">/);
    expect(markup).toMatch(/<select aria-label="Rol de Diagram User"[^>]*>.*?<option value="Editor"[^>]*>Editor<\/option>.*?<option value="Lector"[^>]*>Lector<\/option>.*?<\/select>/);
  });

  it('does not offer role changes or removal for the signed-in owner row', () => {
    const markup = render({ userId: owner.userId, members: [owner], pendingInvitations: [] });

    expect(markup).toContain('Invitar a una persona');
    expect(markup).not.toContain('aria-label="Rol de Project Owner"');
    expect(markup).not.toContain('>Quitar</button>');
  });

  it('shows a readable backend message after a failed invitation without exposing its code or UUID', () => {
    const inviteError = loginErrorMessage(new ApiTransportError(
      'USER_NOT_FOUND',
      'No account was found for that username.',
      [{ id: editor.userId }],
      editor.userId,
      404,
    ));
    const markup = render({ userId: owner.userId, inviteError });

    expect(markup).toContain('No se encontró la cuenta de usuario.');
    expect(markup).not.toContain('USER_NOT_FOUND');
    expect(markup).not.toMatch(UUID_PATTERN);
  });

  it('distinguishes an empty member list from a loading error', () => {
    expect(render({ members: [] })).toContain('Aún no hay integrantes.');
    expect(render({ members: [], membersError: 'Members could not be loaded.' })).toContain('role="alert"');
    expect(render({ members: [], membersError: 'Members could not be loaded.' })).not.toContain('Aún no hay integrantes.');
  });
});
