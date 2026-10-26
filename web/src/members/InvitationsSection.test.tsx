import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ApiTransportError } from '../api/client';
import type { InvitationResource, MemberResource } from '../api/types';
import { loginErrorMessage } from '../pages/LoginPage';
import { acceptInvitationAndRefresh, InvitationsSectionView } from './InvitationsSection';

const invitation: InvitationResource = {
  projectId: '11111111-1111-4111-8111-111111111111',
  projectName: 'Library system',
  id: '22222222-2222-4222-8222-222222222222',
  inviteeUsername: 'diagram.user',
  inviteeDisplayName: 'Diagram User',
  role: 'EDITOR',
  status: 'PENDING',
  invitedByUsername: 'project.owner',
  createdAt: '2026-10-01T12:00:00Z',
};

const acceptedMember: MemberResource = {
  userId: '33333333-3333-4333-8333-333333333333',
  username: 'diagram.user',
  displayName: 'Diagram User',
  role: 'EDITOR',
  status: 'ACTIVE',
};

function renderView(overrides: Partial<Parameters<typeof InvitationsSectionView>[0]> = {}): string {
  return renderToStaticMarkup(createElement(InvitationsSectionView, {
    invitations: [invitation],
    loading: false,
    error: null,
    acceptingInvitationId: null,
    onAccept: vi.fn(),
    ...overrides,
  }));
}

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

describe('project invitations section', () => {
  it('shows pending invitation details and an Accept control without rendering identifiers', () => {
    const markup = renderView({
      invitations: [invitation, { ...invitation, id: '44444444-4444-4444-8444-444444444444', status: 'ACCEPTED' }],
    });

    expect(markup).toContain('Library system');
    expect(markup).toContain('project.owner');
    expect(markup).toContain('acceso de Editor');
    expect(markup).toContain('>Aceptar</button>');
    expect(markup).not.toContain('44444444-4444-4444-8444-444444444444');
    expect(markup).not.toMatch(UUID_PATTERN);
  });

  it('accepts the invitation and refreshes the projects list', async () => {
    const client = { acceptProjectInvitation: vi.fn().mockResolvedValue(acceptedMember) };
    const refreshProjects = vi.fn().mockResolvedValue(undefined);

    await acceptInvitationAndRefresh(client, invitation, refreshProjects);

    expect(client.acceptProjectInvitation).toHaveBeenCalledWith(invitation.projectId, invitation.id);
    expect(refreshProjects).toHaveBeenCalledOnce();
    expect(renderView()).not.toContain(invitation.id);
  });

  it('renders a readable backend failure without its code or UUID', () => {
    const failure = loginErrorMessage(new ApiTransportError(
      'INVITATION_NOT_FOUND',
      'That invitation is no longer available.',
      [{ id: invitation.id }],
      invitation.id,
      404,
    ));
    const markup = renderView({ error: failure });

    expect(markup).toContain('That invitation is no longer available.');
    expect(markup).not.toContain('INVITATION_NOT_FOUND');
    expect(markup).not.toMatch(UUID_PATTERN);
  });

  it('distinguishes an empty list from an error', () => {
    expect(renderView({ invitations: [] })).toContain('No tienes invitaciones pendientes.');
    expect(renderView({ invitations: [], error: 'Invitations could not be loaded.' })).toContain('role="alert"');
    expect(renderView({ invitations: [], error: 'Invitations could not be loaded.' })).not.toContain('No tienes invitaciones pendientes.');
  });
});
