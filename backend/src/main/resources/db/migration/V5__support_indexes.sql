-- ---------------------------------------------------------------------------
-- Support indexes declared by the physical schema authority
-- ---------------------------------------------------------------------------
CREATE INDEX idx_command_model_rev    ON model_command(model_id, base_revision);
CREATE INDEX idx_command_author       ON model_command(author_user_id);
CREATE INDEX idx_version_model_rev    ON model_version(model_id, revision DESC);
CREATE INDEX idx_tombstone_model      ON tombstone(model_id);
