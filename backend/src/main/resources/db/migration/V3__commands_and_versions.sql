-- ---------------------------------------------------------------------------
-- Enumerations used by command and version history
-- ---------------------------------------------------------------------------
CREATE TYPE validation_state AS ENUM ('VALID', 'INVALID', 'PENDING');
CREATE TYPE command_origin AS ENUM ('MANUAL', 'AI', 'VOICE', 'PHOTO', 'XMI', 'UNDO', 'REDO', 'RESOLUTION', 'ADMIN', 'OFFLINE', 'IMPORT');
CREATE TYPE authorization_classification AS ENUM ('AUTHORIZED_ONLINE', 'VERIFIED_PRE_REVOCATION', 'VERIFIED_POST_REVOCATION', 'AMBIGUOUS', 'NOT_APPLICABLE');

-- ---------------------------------------------------------------------------
-- 4. Historial, comandos, versiones, tombstones, conflictos
-- ---------------------------------------------------------------------------
CREATE TABLE model_version (
    version_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id       UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    revision       BIGINT NOT NULL,
    parent_revision BIGINT,
    state_hash     CHAR(64) NOT NULL,
    validation_state validation_state NOT NULL DEFAULT 'PENDING',
    created_by_user_id UUID REFERENCES user_account(user_id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_model_version_revision UNIQUE (model_id, revision)
);

CREATE TABLE model_command (
    command_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id       UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    author_user_id UUID REFERENCES user_account(user_id) ON DELETE SET NULL,
    base_revision  BIGINT NOT NULL,
    result_revision BIGINT,
    operation      VARCHAR(128) NOT NULL,             -- create/edit/move/delete/rename/merge/undo/import/...
    target_type    target_type,
    target_id      UUID,
    payload        JSONB,                             -- sobres de operación variables (precondiciones, argumentos)
    origin         command_origin NOT NULL DEFAULT 'MANUAL',
    authorization_classification authorization_classification NOT NULL DEFAULT 'AUTHORIZED_ONLINE',
    inverse_of_command_id UUID REFERENCES model_command(command_id) ON DELETE SET NULL, -- undo/redo selectivo
    causal_parent_operation_id UUID REFERENCES model_command(command_id) ON DELETE SET NULL, -- orden causal local/remota
    idempotency_key VARCHAR(128),                    -- reenvío seguro de la cola offline sin efectos duplicados
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_command_result_revision CHECK (result_revision IS NULL OR result_revision > base_revision),
    CONSTRAINT uq_command_idempotency UNIQUE (model_id, idempotency_key)
);

COMMENT ON TABLE model_command IS 'Registro append-only lógico. Las correcciones se expresan como comandos nuevos; el SQL recomienda rechazar mutaciones retroactivas en la capa de servicio.';

CREATE TABLE cascade_group (
    cascade_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id       UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    root_command_id UUID NOT NULL REFERENCES model_command(command_id) ON DELETE CASCADE,
    element_count  INT NOT NULL DEFAULT 0,
    restored_at    TIMESTAMPTZ
);

CREATE TABLE tombstone (
    tombstone_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id       UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    target_type    target_type NOT NULL,
    target_id      UUID NOT NULL,
    cascade_id     UUID REFERENCES cascade_group(cascade_id) ON DELETE CASCADE,
    command_id     UUID REFERENCES model_command(command_id) ON DELETE SET NULL,
    deleted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tombstone_target UNIQUE (target_type, target_id)
);
