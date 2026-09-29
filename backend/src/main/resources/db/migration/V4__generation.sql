-- ---------------------------------------------------------------------------
-- Enumerations used by deterministic generation
-- ---------------------------------------------------------------------------
CREATE TYPE generation_state AS ENUM ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED');
CREATE TYPE verification_state AS ENUM ('PASSED', 'FAILED', 'NOT_RUN');

-- ---------------------------------------------------------------------------
-- 7. Generación determinista (P07/P08)
-- ---------------------------------------------------------------------------
-- A target is the stable identity of one independent backend/database
-- destination. It is deliberately separate from profile and template
-- identities, which describe how that destination is generated.
CREATE TABLE generation_target (
    target_id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id                 UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    target_key               VARCHAR(128) NOT NULL,
    name                     VARCHAR(200) NOT NULL,
    database_engine          VARCHAR(32) NOT NULL DEFAULT 'POSTGRESQL',
    database_name            VARCHAR(200) NOT NULL,
    database_schema          VARCHAR(200) NOT NULL DEFAULT 'public',
    connection_config_ref    VARCHAR(512), -- opaque external reference; never a credential
    configuration_revision   INT NOT NULL DEFAULT 1,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_generation_target_key UNIQUE (model_id, target_key),
    CONSTRAINT chk_generation_target_revision CHECK (configuration_revision > 0),
    CONSTRAINT chk_generation_target_engine CHECK (database_engine = 'POSTGRESQL')
);

COMMENT ON TABLE generation_target IS 'Stable identity for an independent generated backend/database. Credentials are external and never persisted; connection_config_ref is only an opaque configuration reference.';

CREATE TABLE generation_profile (
    profile_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id           UUID REFERENCES project(project_id) ON DELETE CASCADE,
    profile_key          VARCHAR(128) NOT NULL,
    name                 VARCHAR(200) NOT NULL,
    revision             INT NOT NULL DEFAULT 1,
    configuration        JSONB NOT NULL DEFAULT '{}', -- non-secret generation options only
    configuration_hash   CHAR(64) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_profile_key_revision UNIQUE (project_id, profile_key, revision),
    CONSTRAINT chk_profile_revision CHECK (revision > 0)
);

CREATE TABLE template_version (
    template_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id     UUID NOT NULL REFERENCES generation_profile(profile_id) ON DELETE CASCADE,
    label          VARCHAR(128) NOT NULL,
    revision       INT NOT NULL DEFAULT 1,
    content_hash   CHAR(64) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_template_profile_revision UNIQUE (profile_id, revision),
    CONSTRAINT chk_template_revision CHECK (revision > 0)
);

CREATE TABLE generation_run (
    run_id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id                UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    version_id              UUID NOT NULL REFERENCES model_version(version_id) ON DELETE CASCADE,
    target_id               UUID NOT NULL REFERENCES generation_target(target_id) ON DELETE CASCADE,
    target_config_revision  INT NOT NULL,
    profile_id              UUID NOT NULL REFERENCES generation_profile(profile_id) ON DELETE CASCADE,
    template_id             UUID NOT NULL REFERENCES template_version(template_id) ON DELETE CASCADE,
    input_snapshot_hash     CHAR(64) NOT NULL,
    artifact_set_hash       CHAR(64),
    artifact_zip_hash       CHAR(64),
    artifact_zip_uri        VARCHAR(1024),
    state                   generation_state NOT NULL DEFAULT 'PENDING',
    started_at              TIMESTAMPTZ,
    finished_at             TIMESTAMPTZ,
    CONSTRAINT uq_run_determinism UNIQUE (target_id, target_config_revision, version_id, profile_id, template_id),
    CONSTRAINT chk_run_target_revision CHECK (target_config_revision > 0)
);

COMMENT ON TABLE generation_run IS 'Deterministic identity is (target and configuration revision, model version, profile revision, template revision). The target allows independent backend/database runs from one model/version; model_id and target consistency are validated by the service.';

CREATE TABLE generated_artifact (
    artifact_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id         UUID NOT NULL REFERENCES generation_run(run_id) ON DELETE CASCADE,
    path           VARCHAR(512) NOT NULL,
    kind           VARCHAR(64) NOT NULL,              -- entity | repository | service | controller | dto | migration | test | ...
    content_hash   CHAR(64) NOT NULL,
    uri            VARCHAR(1024),
    size_bytes     BIGINT,
    CONSTRAINT uq_artifact_run_path UNIQUE (run_id, path)
);

CREATE TABLE verification_result (
    verification_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id         UUID NOT NULL REFERENCES generation_run(run_id) ON DELETE CASCADE,
    check_name     VARCHAR(128) NOT NULL,             -- compile | start | health | contract | determinism | test
    state          verification_state NOT NULL DEFAULT 'NOT_RUN',
    evidence       TEXT,                              -- must identify run; must not contain secrets
    evidence_hash  CHAR(64),
    tool_name      VARCHAR(128),                      -- descriptive; does not fix a tool
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Support indexes declared by the physical schema authority
-- ---------------------------------------------------------------------------
CREATE INDEX idx_generation_target_model ON generation_target(model_id, target_key);
CREATE INDEX idx_run_model            ON generation_run(model_id, state);
CREATE INDEX idx_run_target           ON generation_run(target_id, version_id, state);
CREATE INDEX idx_artifact_run         ON generated_artifact(run_id);
CREATE INDEX idx_verification_run     ON verification_result(run_id);

