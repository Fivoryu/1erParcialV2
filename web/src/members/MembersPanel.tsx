import { useEffect, useState, type FormEvent } from 'react';
import { createApiClient } from '../api/client';
import { loadSession } from '../api/session';
import type {
  AssignableRole,
  CreateInvitationRequest,
  InvitationResource,
  MemberResource,
  MemberRole,
} from '../api/types';
import { loginErrorMessage } from '../pages/LoginPage';

const api = createApiClient();

function roleLabel(role: MemberRole): string {
  switch (role) {
    case 'OWNER':
      return 'Propietario';
    case 'EDITOR':
      return 'Editor';
    case 'VIEWER':
      return 'Lector';
  }
}

function assignableRoleFromLabel(label: string): AssignableRole {
  return label === 'Editor' ? 'EDITOR' : 'VIEWER';
}

export interface MembersPanelViewProps {
  userId: string | null;
  members: MemberResource[];
  pendingInvitations: InvitationResource[];
  loadingMembers: boolean;
  loadingInvitations: boolean;
  membersError: string | null;
  invitationsError: string | null;
  inviteError: string | null;
  actionError: string | null;
  inviteUsername: string;
  inviteRole: AssignableRole;
  inviteBusy: boolean;
  busyMemberId: string | null;
  busyInvitationId: string | null;
  onInviteUsernameChange: (username: string) => void;
  onInviteRoleChange: (role: AssignableRole) => void;
  onInvite: () => void;
  onChangeRole: (member: MemberResource, role: AssignableRole) => void;
  onRemoveMember: (member: MemberResource) => void;
  onRevokeInvitation: (invitation: InvitationResource) => void;
}

export function MembersPanelView({
  userId,
  members,
  pendingInvitations,
  loadingMembers,
  loadingInvitations,
  membersError,
  invitationsError,
  inviteError,
  actionError,
  inviteUsername,
  inviteRole,
  inviteBusy,
  busyMemberId,
  busyInvitationId,
  onInviteUsernameChange,
  onInviteRoleChange,
  onInvite,
  onChangeRole,
  onRemoveMember,
  onRevokeInvitation,
}: MembersPanelViewProps) {
  const currentMember = userId === null
    ? undefined
    : members.find((member) => member.userId === userId && member.status === 'ACTIVE');
  const isOwner = currentMember?.role === 'OWNER';

  return (
    <section className="surface members-panel" aria-labelledby="members-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Acceso al proyecto</p>
          <h2 id="members-title">Integrantes</h2>
        </div>
        <span className="count-chip">{members.length}</span>
      </div>

      {actionError && <div className="notice notice-error" role="alert">{actionError}</div>}

      {membersError ? (
        <div className="notice notice-error" role="alert">{membersError}</div>
      ) : loadingMembers ? (
        <p className="quiet-state" role="status">Cargando integrantes…</p>
      ) : members.length === 0 ? (
        <p className="quiet-state members-empty">Aún no hay integrantes.</p>
      ) : (
        <ul className="members-list">
          {members.map((member) => {
            const isCurrentOwner = member.userId === userId && member.role === 'OWNER';
            const canManage = isOwner && member.status === 'ACTIVE' && !isCurrentOwner;
            return (
              <li className="member-row" key={member.userId}>
                <div className="member-copy">
                  <h3>{member.displayName}</h3>
                  <p>{roleLabel(member.role)}{member.status === 'REMOVED' ? ' · Eliminado' : ''}</p>
                </div>
                {canManage && (
                  <div className="member-controls">
                    <select
                      aria-label={`Rol de ${member.displayName}`}
                      disabled={busyMemberId !== null}
                      onChange={(event) => onChangeRole(member, assignableRoleFromLabel(event.target.value))}
                      value={roleLabel(member.role)}
                    >
                      <option value="Editor">Editor</option>
                      <option value="Lector">Lector</option>
                    </select>
                    <button
                      className="button button-secondary button-compact member-remove-button"
                      disabled={busyMemberId !== null}
                      onClick={() => onRemoveMember(member)}
                      type="button"
                    >
                      {busyMemberId === member.userId ? 'Quitando…' : 'Quitar'}
                    </button>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {isOwner && !membersError && !loadingMembers && (
        <div className="member-admin-grid">
          <section className="member-admin-section" aria-labelledby="invite-member-title">
            <p className="eyebrow">Acceso</p>
            <h3 id="invite-member-title">Invitar a una persona</h3>
            <p className="section-copy">Elige quién puede ver o editar este proyecto.</p>
            {inviteError && <div className="notice notice-error" role="alert">{inviteError}</div>}
            <form
              className="project-form member-invite-form"
              onSubmit={(event: FormEvent<HTMLFormElement>) => {
                event.preventDefault();
                onInvite();
              }}
            >
              <label className="field-label">
                Usuario
                <input
                  autoComplete="off"
                  onChange={(event) => onInviteUsernameChange(event.target.value)}
                  required
                  value={inviteUsername}
                />
              </label>
              <label className="field-label">
                Rol
                <select
                  onChange={(event) => onInviteRoleChange(assignableRoleFromLabel(event.target.value))}
                  value={roleLabel(inviteRole)}
                >
                  <option value="Lector">Lector</option>
                  <option value="Editor">Editor</option>
                </select>
              </label>
              <button className="button button-primary" disabled={inviteBusy} type="submit">
                {inviteBusy ? 'Enviando…' : 'Enviar invitación'}
              </button>
            </form>
          </section>

          <section className="member-admin-section" aria-labelledby="pending-project-invitations-title">
            <p className="eyebrow">Esperando respuesta</p>
            <h3 id="pending-project-invitations-title">Invitaciones pendientes</h3>
            {invitationsError && <div className="notice notice-error" role="alert">{invitationsError}</div>}
            {loadingInvitations ? (
              <p className="quiet-state" role="status">Cargando las invitaciones…</p>
            ) : pendingInvitations.length === 0 ? (
              invitationsError ? null : <p className="quiet-state invitations-empty">No hay invitaciones pendientes.</p>
            ) : (
              <ul className="invitations-list">
                {pendingInvitations.map((invitation) => (
                  <li className="invitation-row" key={invitation.id}>
                    <div className="invitation-copy">
                      <h3>{invitation.inviteeDisplayName || invitation.inviteeUsername}</h3>
                      <p>Acceso de {roleLabel(invitation.role)}</p>
                    </div>
                    <button
                      className="button button-secondary button-compact"
                      disabled={busyInvitationId !== null}
                      onClick={() => onRevokeInvitation(invitation)}
                      type="button"
                    >
                      {busyInvitationId === invitation.id ? 'Revocando…' : 'Revocar'}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}
    </section>
  );
}

interface MembersPanelProps {
  projectId: string;
}

export default function MembersPanel({ projectId }: MembersPanelProps) {
  const [members, setMembers] = useState<MemberResource[]>([]);
  const [pendingInvitations, setPendingInvitations] = useState<InvitationResource[]>([]);
  const [loadingMembers, setLoadingMembers] = useState(true);
  const [loadingInvitations, setLoadingInvitations] = useState(false);
  const [membersError, setMembersError] = useState<string | null>(null);
  const [invitationsError, setInvitationsError] = useState<string | null>(null);
  const [inviteError, setInviteError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [inviteUsername, setInviteUsername] = useState('');
  const [inviteRole, setInviteRole] = useState<AssignableRole>('VIEWER');
  const [inviteBusy, setInviteBusy] = useState(false);
  const [busyMemberId, setBusyMemberId] = useState<string | null>(null);
  const [busyInvitationId, setBusyInvitationId] = useState<string | null>(null);
  const userId = loadSession()?.userId ?? null;

  useEffect(() => {
    let active = true;
    setLoadingMembers(true);
    setMembersError(null);
    void api.listProjectMembers(projectId)
      .then(async (items) => {
        if (!active) {
          return;
        }
        setMembers(items);
        const currentMember = userId === null
          ? undefined
          : items.find((member) => member.userId === userId && member.status === 'ACTIVE');
        if (currentMember?.role !== 'OWNER') {
          setPendingInvitations([]);
          setLoadingInvitations(false);
          return;
        }

        setLoadingInvitations(true);
        setInvitationsError(null);
        try {
          const invitations = await api.listProjectInvitations(projectId);
          if (active) {
            setPendingInvitations(invitations.filter((invitation) => invitation.status === 'PENDING'));
          }
        } catch (cause: unknown) {
          if (active) {
            setInvitationsError(loginErrorMessage(cause));
          }
        } finally {
          if (active) {
            setLoadingInvitations(false);
          }
        }
      })
      .catch((cause: unknown) => {
        if (active) {
          setMembersError(loginErrorMessage(cause));
        }
      })
      .finally(() => {
        if (active) {
          setLoadingMembers(false);
        }
      });

    return () => {
      active = false;
    };
  }, [projectId, userId]);

  const isOwner = userId !== null && members.some((member) => (
    member.userId === userId && member.role === 'OWNER' && member.status === 'ACTIVE'
  ));

  async function handleInvite(): Promise<void> {
    const username = inviteUsername.trim();
    if (!isOwner || inviteBusy || !username) {
      return;
    }

    setInviteBusy(true);
    setInviteError(null);
    try {
      const request: CreateInvitationRequest = { username, role: inviteRole };
      const invitation = await api.createProjectInvitation(projectId, request);
      setPendingInvitations((current) => [
        ...current.filter((item) => item.id !== invitation.id),
        invitation,
      ].filter((item) => item.status === 'PENDING'));
      setInviteUsername('');
    } catch (cause: unknown) {
      setInviteError(loginErrorMessage(cause));
    } finally {
      setInviteBusy(false);
    }
  }

  async function handleRoleChange(member: MemberResource, role: AssignableRole): Promise<void> {
    if (!isOwner || (member.userId === userId && member.role === 'OWNER') || busyMemberId !== null) {
      return;
    }

    setBusyMemberId(member.userId);
    setActionError(null);
    try {
      const updated = await api.updateProjectMemberRole(projectId, member.userId, { role });
      setMembers((current) => current.map((item) => item.userId === updated.userId ? updated : item));
    } catch (cause: unknown) {
      setActionError(loginErrorMessage(cause));
    } finally {
      setBusyMemberId(null);
    }
  }

  async function handleRemoveMember(member: MemberResource): Promise<void> {
    if (!isOwner || (member.userId === userId && member.role === 'OWNER') || busyMemberId !== null) {
      return;
    }

    setBusyMemberId(member.userId);
    setActionError(null);
    try {
      const removed = await api.removeProjectMember(projectId, member.userId);
      setMembers((current) => current.map((item) => item.userId === removed.userId ? removed : item));
    } catch (cause: unknown) {
      setActionError(loginErrorMessage(cause));
    } finally {
      setBusyMemberId(null);
    }
  }

  async function handleRevokeInvitation(invitation: InvitationResource): Promise<void> {
    if (!isOwner || busyInvitationId !== null) {
      return;
    }

    setBusyInvitationId(invitation.id);
    setInvitationsError(null);
    try {
      await api.revokeProjectInvitation(projectId, invitation.id);
      setPendingInvitations((current) => current.filter((item) => item.id !== invitation.id));
    } catch (cause: unknown) {
      setInvitationsError(loginErrorMessage(cause));
    } finally {
      setBusyInvitationId(null);
    }
  }

  return (
    <MembersPanelView
      actionError={actionError}
      busyInvitationId={busyInvitationId}
      busyMemberId={busyMemberId}
      inviteBusy={inviteBusy}
      inviteError={inviteError}
      inviteRole={inviteRole}
      inviteUsername={inviteUsername}
      invitationsError={invitationsError}
      loadingInvitations={loadingInvitations}
      loadingMembers={loadingMembers}
      members={members}
      membersError={membersError}
      onChangeRole={(member, role) => void handleRoleChange(member, role)}
      onInvite={() => void handleInvite()}
      onInviteRoleChange={setInviteRole}
      onInviteUsernameChange={setInviteUsername}
      onRemoveMember={(member) => void handleRemoveMember(member)}
      onRevokeInvitation={(invitation) => void handleRevokeInvitation(invitation)}
      pendingInvitations={pendingInvitations}
      userId={userId}
    />
  );
}
