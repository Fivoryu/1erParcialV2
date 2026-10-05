-- ---------------------------------------------------------------------------
-- IA4-04: undo/redo colaborativo por compensación (comando inverso), no snapshot.
-- Una fila por comando aceptado capturado en la única entrada HTTP de comandos.
-- ---------------------------------------------------------------------------
CREATE TABLE undo_log (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id     UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    user_id      UUID REFERENCES user_account(user_id) ON DELETE SET NULL,
    command_id   UUID NOT NULL REFERENCES model_command(command_id) ON DELETE CASCADE,
    type         VARCHAR(128) NOT NULL,
    pre_snapshot JSONB,
    undone_at    TIMESTAMPTZ,
    redone_at    TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_undo_log_model_user ON undo_log(model_id, user_id, created_at);

COMMENT ON TABLE undo_log IS 'IA4-04: valor previo de los targets por comando aceptado. Deshacer = comando inverso nuevo (origin UNDO) por el ejecutor normal; nunca snapshot.';
