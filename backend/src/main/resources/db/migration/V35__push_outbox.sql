-- Events are persisted in the same transaction as the action that produces them.
-- A sender will deliver them in a later implementation slice.
CREATE TABLE diagrammer.push_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES diagrammer.project(project_id) ON DELETE CASCADE,
    merge_request_id UUID NOT NULL REFERENCES diagrammer.vcs_merge_request(merge_request_id) ON DELETE CASCADE,
    recipient_id UUID NOT NULL REFERENCES diagrammer.user_account(user_id) ON DELETE CASCADE,
    event_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ
);

CREATE INDEX idx_push_outbox_pending ON diagrammer.push_outbox(next_attempt_at) WHERE delivered_at IS NULL;
