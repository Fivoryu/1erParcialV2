-- The design left the identity provider as an open gap. These are local credentials;
-- external_subject remains the opaque reference so a real provider can take over and
-- password_hash can be dropped later.
ALTER TABLE diagrammer.user_account
    ADD COLUMN username CITEXT,
    ADD COLUMN password_hash VARCHAR(100);

UPDATE diagrammer.user_account
SET username = 'seed-owner',
    password_hash = '$2a$10$4Bru3384rPJobK6U42fAnes76sFVZ40iyeSvBeDc1SeVpXjoQHocK'
WHERE user_id = '00000000-0000-0000-0000-000000000001';

ALTER TABLE diagrammer.user_account
    ALTER COLUMN username SET NOT NULL,
    ALTER COLUMN password_hash SET NOT NULL,
    ADD CONSTRAINT uq_user_username UNIQUE (username);

CREATE TABLE diagrammer.user_session (
    session_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES diagrammer.user_account(user_id) ON DELETE CASCADE,
    token_hash   CHAR(64) NOT NULL UNIQUE,
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ
);

CREATE INDEX idx_session_user ON diagrammer.user_session(user_id);

COMMENT ON TABLE diagrammer.user_session IS 'Session record; the raw bearer token is never stored. Presence and the editing lease are ephemeral by contrast.';
