-- Events that exhaust their retries are abandoned instead of being retried forever.
ALTER TABLE diagrammer.push_outbox ADD COLUMN abandoned_at TIMESTAMPTZ;
DROP INDEX diagrammer.idx_push_outbox_pending;
CREATE INDEX idx_push_outbox_pending ON diagrammer.push_outbox(next_attempt_at)
    WHERE delivered_at IS NULL AND abandoned_at IS NULL;
