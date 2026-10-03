CREATE TYPE diagrammer.invitation_status AS ENUM ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED');

CREATE TABLE diagrammer.invitation (
    invitation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES diagrammer.project(project_id) ON DELETE CASCADE,
    invited_by_user_id UUID NOT NULL REFERENCES diagrammer.user_account(user_id) ON DELETE RESTRICT,
    invitee_user_id UUID REFERENCES diagrammer.user_account(user_id) ON DELETE SET NULL,
    role diagrammer.project_member_role NOT NULL DEFAULT 'VIEWER',
    status diagrammer.invitation_status NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_invitation_project
    ON diagrammer.invitation(project_id, created_at, invitation_id);

CREATE UNIQUE INDEX uq_invitation_pending_project_invitee
    ON diagrammer.invitation(project_id, invitee_user_id)
    WHERE status = 'PENDING';

COMMENT ON TABLE diagrammer.invitation IS
    'Invitation carries the proposed role; revoking it never erases operation history.';
