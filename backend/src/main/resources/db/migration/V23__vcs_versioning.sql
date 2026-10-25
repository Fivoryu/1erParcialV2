-- ---------------------------------------------------------------------------
-- Incremento 5 — versionado tipo Git para diagramas.
-- Un commit guarda un snapshot canónico e inmutable del modelo. Las ramas son
-- punteros con nombre a un commit; el autoguardado escribe la copia de trabajo y
-- NO crea commits (el commit es siempre explícito). Los permisos finos se
-- configuran por diagrama (hoy: el modelo del proyecto) y por usuario, y el
-- principal `operator` los ignora con acceso total.
-- ---------------------------------------------------------------------------

CREATE TYPE diagrammer.vcs_commit_kind AS ENUM ('INITIAL', 'MANUAL', 'MERGE');
CREATE TYPE diagrammer.vcs_commit_origin AS ENUM ('MANUAL', 'AI', 'VOICE', 'IMPORT', 'MERGE');
CREATE TYPE diagrammer.vcs_merge_state AS ENUM ('OPEN', 'MERGED', 'CLOSED');
CREATE TYPE diagrammer.vcs_action AS ENUM (
    'VIEW', 'CREATE', 'ADD', 'DELETE', 'COMMIT', 'BRANCH', 'MERGE', 'MANAGE_PERMISSIONS');

-- Objeto inmutable y deduplicado: el mismo estado del modelo no se guarda dos veces.
CREATE TABLE diagrammer.vcs_snapshot (
    snapshot_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id    UUID NOT NULL REFERENCES diagrammer.uml_model(model_id) ON DELETE CASCADE,
    state_hash  CHAR(64) NOT NULL,
    payload     JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vcs_snapshot_model_hash UNIQUE (model_id, state_hash)
);

CREATE INDEX idx_vcs_snapshot_model ON diagrammer.vcs_snapshot(model_id);

COMMENT ON TABLE diagrammer.vcs_snapshot IS
    'Estado canónico completo del diagrama en un instante. Inmutable: un snapshot nunca se edita, se referencia.';

CREATE TABLE diagrammer.vcs_commit (
    commit_id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id                UUID NOT NULL REFERENCES diagrammer.uml_model(model_id) ON DELETE CASCADE,
    parent_commit_id        UUID REFERENCES diagrammer.vcs_commit(commit_id) ON DELETE SET NULL,
    second_parent_commit_id UUID REFERENCES diagrammer.vcs_commit(commit_id) ON DELETE SET NULL,
    snapshot_id             UUID NOT NULL REFERENCES diagrammer.vcs_snapshot(snapshot_id) ON DELETE RESTRICT,
    branch_name             VARCHAR(120) NOT NULL,
    kind                    diagrammer.vcs_commit_kind NOT NULL DEFAULT 'MANUAL',
    origin                  diagrammer.vcs_commit_origin NOT NULL DEFAULT 'MANUAL',
    message                 VARCHAR(500) NOT NULL,
    author_user_id          UUID REFERENCES diagrammer.user_account(user_id) ON DELETE SET NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_vcs_commit_model ON diagrammer.vcs_commit(model_id, created_at DESC, commit_id);
CREATE INDEX idx_vcs_commit_author ON diagrammer.vcs_commit(author_user_id);

COMMENT ON TABLE diagrammer.vcs_commit IS
    'Un commit es explícito: el autoguardado no lo crea. El segundo padre sólo existe en commits MERGE.';

CREATE TABLE diagrammer.vcs_branch (
    branch_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id           UUID NOT NULL REFERENCES diagrammer.uml_model(model_id) ON DELETE CASCADE,
    name               VARCHAR(120) NOT NULL,
    head_commit_id     UUID NOT NULL REFERENCES diagrammer.vcs_commit(commit_id) ON DELETE RESTRICT,
    is_protected       BOOLEAN NOT NULL DEFAULT FALSE,
    created_by_user_id UUID REFERENCES diagrammer.user_account(user_id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vcs_branch_model_name UNIQUE (model_id, name)
);

CREATE INDEX idx_vcs_branch_model ON diagrammer.vcs_branch(model_id);

COMMENT ON TABLE diagrammer.vcs_branch IS
    'main nace con el modelo y es la base de la generación de backend. is_protected restringe quién commitea directo.';

CREATE TABLE diagrammer.vcs_merge_request (
    merge_request_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id              UUID NOT NULL REFERENCES diagrammer.uml_model(model_id) ON DELETE CASCADE,
    source_branch_id      UUID NOT NULL REFERENCES diagrammer.vcs_branch(branch_id) ON DELETE CASCADE,
    target_branch_id      UUID NOT NULL REFERENCES diagrammer.vcs_branch(branch_id) ON DELETE CASCADE,
    source_commit_id      UUID NOT NULL REFERENCES diagrammer.vcs_commit(commit_id) ON DELETE RESTRICT,
    target_commit_id      UUID NOT NULL REFERENCES diagrammer.vcs_commit(commit_id) ON DELETE RESTRICT,
    merge_base_commit_id  UUID REFERENCES diagrammer.vcs_commit(commit_id) ON DELETE SET NULL,
    merged_commit_id      UUID REFERENCES diagrammer.vcs_commit(commit_id) ON DELETE SET NULL,
    state                 diagrammer.vcs_merge_state NOT NULL DEFAULT 'OPEN',
    has_conflicts         BOOLEAN NOT NULL DEFAULT FALSE,
    conflicts             JSONB,
    title                 VARCHAR(200) NOT NULL,
    opened_by_user_id     UUID REFERENCES diagrammer.user_account(user_id) ON DELETE SET NULL,
    decided_by_user_id    UUID REFERENCES diagrammer.user_account(user_id) ON DELETE SET NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_vcs_merge_request_branches CHECK (source_branch_id <> target_branch_id)
);

CREATE INDEX idx_vcs_merge_request_model ON diagrammer.vcs_merge_request(model_id, state);
CREATE INDEX idx_vcs_merge_request_source ON diagrammer.vcs_merge_request(source_branch_id);

COMMENT ON TABLE diagrammer.vcs_merge_request IS
    'PR entre dos ramas. Los conflictos se calculan por elemento y campo contra la base de merge y se congelan al abrir.';

CREATE TABLE diagrammer.vcs_permission (
    permission_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id            UUID NOT NULL REFERENCES diagrammer.uml_model(model_id) ON DELETE CASCADE,
    user_id             UUID NOT NULL REFERENCES diagrammer.user_account(user_id) ON DELETE CASCADE,
    action              diagrammer.vcs_action NOT NULL,
    granted_by_user_id  UUID REFERENCES diagrammer.user_account(user_id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vcs_permission_model_user_action UNIQUE (model_id, user_id, action)
);

CREATE INDEX idx_vcs_permission_model_user ON diagrammer.vcs_permission(model_id, user_id);

COMMENT ON TABLE diagrammer.vcs_permission IS
    'Permiso fino por diagrama y usuario. La ausencia de fila no concede nada; el rol del proyecto no se reemplaza, se complementa.';
