CREATE TABLE diagrammer.push_device (
    device_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES diagrammer.user_account(user_id) ON DELETE CASCADE,
    token VARCHAR(4096) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_push_device_user ON diagrammer.push_device(user_id);
