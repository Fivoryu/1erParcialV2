import { useEffect, useState } from 'react';
import { createApiClient, type ApiClient } from '../api/client';
import type { InvitationResource, MemberRole } from '../api/types';
import { loginErrorMessage } from '../pages/LoginPage';

const api = createApiClient();

function roleLabel(role: MemberRole): string {
  switch (role) {
    case 'OWNER':
      return 'Owner';
    case 'EDITOR':
      return 'Editor';
    case 'VIEWER':
      return 'Viewer';
  }
}

export interface InvitationsSectionViewProps {
  invitations: InvitationResource[];
  loading: boolean;
  error: string | null;
  acceptingInvitationId: string | null;
  onAccept: (invitation: InvitationResource) => void;
}

export function InvitationsSectionView({
  invitations,
  loading,
  error,
  acceptingInvitationId,
  onAccept,
}: InvitationsSectionViewProps) {
  const pendingInvitations = invitations.filter((invitation) => invitation.status === 'PENDING');

  return (
    <section className="surface invitations-panel" aria-labelledby="invitations-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">For you</p>
          <h2 id="invitations-title">Project invitations</h2>
        </div>
      </div>

      {error && <div className="notice notice-error" role="alert">{error}</div>}

      {loading ? (
        <p className="quiet-state" role="status">Loading invitations…</p>
      ) : pendingInvitations.length === 0 ? (
        error ? null : <p className="quiet-state invitations-empty">No pending invitations.</p>
      ) : (
        <ul className="invitations-list">
          {pendingInvitations.map((invitation) => (
            <li className="invitation-row" key={invitation.id}>
              <div className="invitation-copy">
                <h3>{invitation.projectName}</h3>
                <p>Invited by <strong>{invitation.invitedByUsername}</strong> · {roleLabel(invitation.role)} access</p>
              </div>
              <button
                className="button button-primary button-compact"
                disabled={acceptingInvitationId !== null}
                onClick={() => onAccept(invitation)}
                type="button"
              >
                {acceptingInvitationId === invitation.id ? 'Accepting…' : 'Accept'}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export async function acceptInvitationAndRefresh(
  client: Pick<ApiClient, 'acceptProjectInvitation'>,
  invitation: InvitationResource,
  refreshProjects: () => Promise<unknown> | unknown,
): Promise<void> {
  await client.acceptProjectInvitation(invitation.projectId, invitation.id);
  await refreshProjects();
}

interface InvitationsSectionProps {
  onProjectAccepted: () => Promise<unknown> | unknown;
}

export default function InvitationsSection({ onProjectAccepted }: InvitationsSectionProps) {
  const [invitations, setInvitations] = useState<InvitationResource[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [acceptingInvitationId, setAcceptingInvitationId] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    void api.listMyInvitations()
      .then((items) => {
        if (active) {
          setInvitations(items);
          setError(null);
        }
      })
      .catch((cause: unknown) => {
        if (active) {
          setError(loginErrorMessage(cause));
        }
      })
      .finally(() => {
        if (active) {
          setLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, []);

  async function handleAccept(invitation: InvitationResource): Promise<void> {
    if (acceptingInvitationId !== null) {
      return;
    }

    setAcceptingInvitationId(invitation.id);
    setError(null);
    try {
      await acceptInvitationAndRefresh(api, invitation, onProjectAccepted);
      setInvitations((current) => current.filter((item) => item.id !== invitation.id));
    } catch (cause: unknown) {
      setError(loginErrorMessage(cause));
    } finally {
      setAcceptingInvitationId(null);
    }
  }

  return (
    <InvitationsSectionView
      acceptingInvitationId={acceptingInvitationId}
      error={error}
      invitations={invitations}
      loading={loading}
      onAccept={(invitation) => void handleAccept(invitation)}
    />
  );
}
