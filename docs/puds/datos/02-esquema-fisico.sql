-- ============================================================================
-- Diagramador colaborativo de clases UML 2.5 — Esquema físico propuesto (D2)
-- ----------------------------------------------------------------------------
-- Dominio persistido: metadata del diagramador (proyectos, modelo UML,
-- disposición compartida, versiones, comandos, conflictos, XMI, generación,
-- auditoría). NO modela el dominio que el docente entrega en el examen: ese
-- dominio se crea posteriormente por los usuarios dentro de un proyecto.
--
-- Decisiones de mapeo (ver 01-mapeo-base-de-datos.md):
--   * Identidad estable UUID; el nombre visible nunca es identidad.
--   * PostgreSQL en esquema dedicado `diagrammer`.
--   * JSONB solo para payloads variables (sobres de comandos, precondiciones,
--     diagnósticos, propuestas, configuraciones); no sustituye relaciones.
--   * Los nombres UML duplicados NO están prohibidos por UNIQUE; la restricción
--     se registra en name_collision y bloquea validación/publicación/XMI/
--     generación hasta resolverla.
--   * El borrado funcional es delete-wins con tombstone y cascadas lógicas
--     atómicas; no se depende de DELETE físico en el flujo normal.
--   * ModelVersion y ModelCommand son append-only lógicos; el undo/redo crea
--     comandos inversos nuevos (inverse_of_command_id).
--
-- Invariantes que el SQL no puede garantizar solo (deben aplicarse en
-- servicio/transacción y se documentan junto a cada tabla):
--   * una relationship tiene exactamente dos relationship_end válidos del
--     mismo modelo;
--   * una cascada delete-wins se confirma como conjunto atómico y un undo
--     invierte el conjunto completo;
--   * cada comando está autorizado en el servidor y deriva de una versión
--     completamente validada;
--   * la clasificación de revocación offline (pre/post/ambigua) usa evidencia
--     temporal y autoridad del servidor;
--   * una generación consume exclusivamente una versión validada;
--   * target, modelo, versión, perfil y plantilla pertenecen al mismo proyecto;
--   * las credenciales nunca se persisten ni aparecen en código, ZIPs, logs o hashes.
--
-- Compatibilidad: PostgreSQL 15+. Orden de ejecución dependiente (padres
-- antes que hijos). Las extensiones usan IF NOT EXISTS.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS diagrammer;
SET search_path TO diagrammer, public;

-- Extensiones de uso habitual; pueden omitirse si la plataforma no las permite.
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS citext;     -- correo/identificadores comparables

-- ---------------------------------------------------------------------------
-- Enumeraciones
-- ---------------------------------------------------------------------------
CREATE TYPE project_member_role AS ENUM ('OWNER', 'EDITOR', 'VIEWER');
CREATE TYPE invitation_status AS ENUM ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED');
CREATE TYPE classifier_kind AS ENUM ('CLASS', 'ABSTRACT_CLASS', 'INTERFACE', 'ENUMERATION', 'PACKAGE');
CREATE TYPE relationship_kind AS ENUM ('ASSOCIATION', 'AGGREGATION', 'COMPOSITION', 'GENERALIZATION', 'INTERFACE_REALIZATION', 'DEPENDENCY');
CREATE TYPE navigation_kind AS ENUM ('NAVIGABLE', 'NOT_NAVIGABLE', 'UNSPECIFIED');
CREATE TYPE multiplicity_kind AS ENUM ('EXACTLY_ONE', 'ZERO_OR_ONE', 'ONE_OR_MANY', 'ZERO_OR_MANY', 'RANGE');
CREATE TYPE severity_kind AS ENUM ('INFO', 'WARNING', 'ERROR');
CREATE TYPE validation_state AS ENUM ('VALID', 'INVALID', 'PENDING');
CREATE TYPE command_origin AS ENUM ('MANUAL', 'AI', 'VOICE', 'PHOTO', 'XMI', 'UNDO', 'REDO', 'RESOLUTION', 'ADMIN', 'OFFLINE', 'IMPORT');
CREATE TYPE authorization_classification AS ENUM ('AUTHORIZED_ONLINE', 'VERIFIED_PRE_REVOCATION', 'VERIFIED_POST_REVOCATION', 'AMBIGUOUS', 'NOT_APPLICABLE');
CREATE TYPE proposal_status AS ENUM ('DRAFT', 'REVIEWED', 'APPLIED', 'DISCARDED');
CREATE TYPE exchange_direction AS ENUM ('IMPORT', 'EXPORT');
CREATE TYPE exchange_status AS ENUM ('SUCCESS', 'PARTIAL', 'FAILED');
CREATE TYPE generation_state AS ENUM ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED');
CREATE TYPE verification_state AS ENUM ('PASSED', 'FAILED', 'NOT_RUN');
CREATE TYPE deployment_state AS ENUM ('PENDING', 'RUNNING', 'UP', 'FAILED', 'STOPPING', 'STOPPED');
CREATE TYPE conflict_state AS ENUM ('OPEN', 'RESOLVED', 'DISMISSED');
CREATE TYPE collision_state AS ENUM ('OPEN', 'RESOLVED');
CREATE TYPE target_type AS ENUM ('CLASSIFIER', 'ATTRIBUTE', 'OPERATION', 'PARAMETER', 'RELATIONSHIP', 'RELATIONSHIP_END', 'CONSTRAINT', 'DIAGRAM_VIEW');
CREATE TYPE audit_outcome AS ENUM ('SUCCESS', 'REJECTED', 'FAILED', 'AMBIGUOUS');

-- ---------------------------------------------------------------------------
-- 1. Identidad, proyectos y acceso
-- ---------------------------------------------------------------------------
CREATE TABLE user_account (
    user_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_subject VARCHAR(512) NOT NULL,          -- referencia opaca al sistema de identidad (no fija proveedor)
    display_name   VARCHAR(160) NOT NULL,
    email          CITEXT,
    status         VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    revoked_at     TIMESTAMPTZ,                       -- revocación no elimina historial
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_external_subject UNIQUE (external_subject),
    CONSTRAINT uq_user_email UNIQUE (email),
    CONSTRAINT chk_user_status CHECK (status IN ('ACTIVE', 'DISABLED', 'REVOKED'))
);

COMMENT ON TABLE user_account IS 'Usuario del diagramador. La autenticación y el proveedor son decisiones D3; aquí solo se conserva identidad referencial y estado.';

CREATE TABLE project (
    project_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id  UUID NOT NULL REFERENCES user_account(user_id) ON DELETE RESTRICT,
    name           VARCHAR(200) NOT NULL,
    description    TEXT,
    archived_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE project_member (
    project_id     UUID NOT NULL REFERENCES project(project_id) ON DELETE CASCADE,
    user_id        UUID NOT NULL REFERENCES user_account(user_id) ON DELETE CASCADE,
    role           project_member_role NOT NULL DEFAULT 'VIEWER',
    status         VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    joined_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT chk_member_status CHECK (status IN ('ACTIVE', 'REMOVED'))
);

COMMENT ON TABLE project_member IS 'Membresía por proyecto. La autorización por comando se valida en el servidor; esta tabla cubre permisos básicos (owner/editor/viewer).';

CREATE TABLE invitation (
    invitation_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id     UUID NOT NULL REFERENCES project(project_id) ON DELETE CASCADE,
    invited_by_user_id UUID NOT NULL REFERENCES user_account(user_id) ON DELETE RESTRICT,
    invitee_user_id    UUID REFERENCES user_account(user_id) ON DELETE SET NULL, -- puede todavía no existir
    status         invitation_status NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 2. Modelo semántico UML
-- ---------------------------------------------------------------------------
CREATE TABLE uml_model (
    model_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id     UUID NOT NULL UNIQUE REFERENCES project(project_id) ON DELETE CASCADE,
    name           VARCHAR(200) NOT NULL,
    current_revision BIGINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE classifier (
    classifier_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id       UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    namespace_id   UUID REFERENCES classifier(classifier_id) ON DELETE SET NULL, -- paquete contenedor opcional
    kind           classifier_kind NOT NULL DEFAULT 'CLASS',
    name           VARCHAR(255) NOT NULL,             -- SIN UNIQUE: pueden existir duplicados controlados
    is_abstract    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_classifier_self_namespace CHECK (namespace_id IS NULL OR namespace_id <> classifier_id)
);

CREATE INDEX idx_classifier_model ON classifier(model_id);
    
CREATE TABLE type_reference (
type_reference_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
model_id       UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
classifier_id  UUID REFERENCES classifier(classifier_id) ON DELETE CASCADE, -- cuando referencia un clasificador
primitive_name VARCHAR(64),                                                  -- cuando es un tipo primitivo/catálogo
created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
CONSTRAINT chk_type_reference CHECK ((classifier_id IS NOT NULL AND primitive_name IS NULL) OR (classifier_id IS NULL AND primitive_name IS NOT NULL))
);
    
CREATE TABLE uml_attribute (
    attribute_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    classifier_id  UUID NOT NULL REFERENCES classifier(classifier_id) ON DELETE CASCADE,
    type_reference_id UUID REFERENCES type_reference(type_reference_id) ON DELETE SET NULL,
    name           VARCHAR(255) NOT NULL,
    kind           VARCHAR(32) NOT NULL DEFAULT 'ATTRIBUTE',  -- attribute | static | derived...
    visibility     VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    multiplicity   multiplicity_kind NOT NULL DEFAULT 'EXACTLY_ONE',
    default_value  TEXT,
    ordinal        INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_attribute_classifier_ordinal UNIQUE (classifier_id, ordinal)
);

CREATE TABLE uml_operation (
    operation_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    classifier_id  UUID NOT NULL REFERENCES classifier(classifier_id) ON DELETE CASCADE,
    name           VARCHAR(255) NOT NULL,
    return_type_reference_id UUID REFERENCES type_reference(type_reference_id) ON DELETE SET NULL,
    visibility     VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    is_abstract    BOOLEAN NOT NULL DEFAULT FALSE,
    is_static      BOOLEAN NOT NULL DEFAULT FALSE,
    ordinal        INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_operation_classifier_ordinal UNIQUE (classifier_id, ordinal)
);

CREATE TABLE operation_parameter (
    parameter_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operation_id   UUID NOT NULL REFERENCES uml_operation(operation_id) ON DELETE CASCADE,
    type_reference_id UUID REFERENCES type_reference(type_reference_id) ON DELETE SET NULL,
    name           VARCHAR(255) NOT NULL,
    direction      VARCHAR(16) NOT NULL DEFAULT 'IN',   -- IN | OUT | INOUT
    ordinal        INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_parameter_operation_ordinal UNIQUE (operation_id, ordinal)
);

CREATE TABLE uml_relationship (
    relationship_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id       UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    kind           relationship_kind NOT NULL DEFAULT 'ASSOCIATION',
    name           VARCHAR(255),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE relationship_end (
    relationship_end_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id UUID NOT NULL REFERENCES uml_relationship(relationship_id) ON DELETE CASCADE,
    classifier_id  UUID NOT NULL REFERENCES classifier(classifier_id) ON DELETE CASCADE,
    ordinal        SMALLINT NOT NULL CHECK (ordinal IN (1,2)),
    role           VARCHAR(255),
    multiplicity   multiplicity_kind NOT NULL DEFAULT 'EXACTLY_ONE',
    navigability   navigation_kind NOT NULL DEFAULT 'UNSPECIFIED',
    aggregation_kind VARCHAR(16) NOT NULL DEFAULT 'NONE', -- NONE | SHARED | COMPOSITE
    CONSTRAINT uq_relationship_end_ordinal UNIQUE (relationship_id, ordinal)
);

CREATE TABLE uml_constraint (
    constraint_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id       UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    owner_type     target_type,
    owner_id       UUID,
    expression     TEXT NOT NULL,
    severity       severity_kind NOT NULL DEFAULT 'ERROR',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 3. Disposición compartida (semántica separada del layout)
-- ---------------------------------------------------------------------------
CREATE TABLE diagram_view (
    view_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id       UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    name           VARCHAR(200) NOT NULL,
    view_kind      VARCHAR(32) NOT NULL DEFAULT 'CLASS',
    is_default     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE element_geometry (
    geometry_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    view_id        UUID NOT NULL REFERENCES diagram_view(view_id) ON DELETE CASCADE,
    target_type    target_type NOT NULL,
    target_id      UUID NOT NULL,          -- polimórfico: debe existir según target_type (validado en aplicación)
    x              INT NOT NULL DEFAULT 0,
    y              INT NOT NULL DEFAULT 0,
    w              INT NOT NULL DEFAULT 1,
    h              INT NOT NULL DEFAULT 1,
    z_order        INT NOT NULL DEFAULT 0,
    CONSTRAINT chk_geometry_dims CHECK (w > 0 AND h > 0),
    CONSTRAINT uq_geometry_view_target UNIQUE (view_id, target_type, target_id)
);

CREATE TABLE connector_path (
    path_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    view_id        UUID NOT NULL REFERENCES diagram_view(view_id) ON DELETE CASCADE,
    relationship_id UUID NOT NULL REFERENCES uml_relationship(relationship_id) ON DELETE CASCADE,
    ordinal        INT NOT NULL DEFAULT 0,
    x              INT NOT NULL DEFAULT 0,
    y              INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_path_view_relationship_ordinal UNIQUE (view_id, relationship_id, ordinal)
);

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

CREATE TABLE model_conflict (
    conflict_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id       UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    target_type    target_type,
    target_id      UUID,
    kind           VARCHAR(64) NOT NULL,              -- semantic | merge | delete-edit | ...
    payload_base   JSONB,
    payload_left   JSONB,
    payload_right  JSONB,
    state          conflict_state NOT NULL DEFAULT 'OPEN',
    resolved_by_command_id UUID REFERENCES model_command(command_id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE name_collision (
    collision_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id       UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    namespace_id   UUID REFERENCES classifier(classifier_id) ON DELETE CASCADE,
    name           VARCHAR(255) NOT NULL,
    state          collision_state NOT NULL DEFAULT 'OPEN',
    detected_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE name_collision IS 'Registra nombres UML duplicados SIN prohibirlos en classifier.name. La resolución (renombrar, mover o merge explícito) desbloquea validación final, XMI definitivo, generación y despliegue.';

-- ---------------------------------------------------------------------------
-- 5. Propuestas asistidas (IA, voz, fotografía)
-- ---------------------------------------------------------------------------
CREATE TABLE ai_proposal (
    proposal_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id       UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    author_user_id UUID REFERENCES user_account(user_id) ON DELETE SET NULL,
    base_revision  BIGINT NOT NULL,
    source         VARCHAR(32) NOT NULL,              -- TEXT | VOICE | PHOTO
    confidence     NUMERIC(5,4),
    payload        JSONB NOT NULL,                    -- comandos candidatos / propuesta estructurada
    status         proposal_status NOT NULL DEFAULT 'DRAFT',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_proposal_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE TABLE detected_element (
    detected_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id    UUID NOT NULL REFERENCES ai_proposal(proposal_id) ON DELETE CASCADE,
    external_key   VARCHAR(255),
    label          VARCHAR(255) NOT NULL,
    confidence     NUMERIC(5,4),
    x              INT, y INT, w INT, h INT,
    accepted_classifier_id UUID REFERENCES classifier(classifier_id) ON DELETE SET NULL,
    is_ambiguous   BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_detected_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

-- ---------------------------------------------------------------------------
-- 6. Interoperabilidad XMI (Q3)
-- ---------------------------------------------------------------------------
CREATE TABLE xmi_exchange (
    exchange_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id       UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    direction      exchange_direction NOT NULL,
    profile        VARCHAR(255) NOT NULL,             -- EA 15.0.1514 / XMI 2.1 / UML 2.1 / extensiones EA 6.5
    model_revision BIGINT,
    checksum       CHAR(64),
    status         exchange_status NOT NULL DEFAULT 'SUCCESS',
    artifact_ref   VARCHAR(512),                      -- referencia a archivo/snapshot (no presupone bucket)
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE xmi_loss_item (
    loss_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exchange_id    UUID NOT NULL REFERENCES xmi_exchange(exchange_id) ON DELETE CASCADE,
    severity       severity_kind NOT NULL DEFAULT 'WARNING',
    external_path  VARCHAR(512),
    message        TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

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
-- 8. Despliegue del backend generado
-- ---------------------------------------------------------------------------
CREATE TABLE deployment (
    deployment_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id             UUID NOT NULL REFERENCES generation_run(run_id) ON DELETE CASCADE,
    target_id          UUID NOT NULL REFERENCES generation_target(target_id) ON DELETE CASCADE,
    state              deployment_state NOT NULL DEFAULT 'PENDING',
    http_host_port     INT NOT NULL,                  -- published on every interface: a client on the network uses it
    postgres_host_port INT NOT NULL,                  -- published on 127.0.0.1 only
    compose_project    VARCHAR(128),                  -- Compose project name reported by the operator; never a host path
    operator_id        VARCHAR(128),                  -- which operator holds the claim
    failure_code       VARCHAR(64),
    failure_message    VARCHAR(1024),                 -- sanitized: credentials never reach this column
    requested_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_deployment_ports CHECK (http_host_port BETWEEN 1024 AND 65535
                                       AND postgres_host_port BETWEEN 1024 AND 65535)
);

COMMENT ON TABLE deployment IS 'One generated backend running on this machine. It is tied to an exact generation run, never to the latest run of its destination, so the bytes that ended up deployed are always the frozen artifact. The ports live on the machine and not in the model, so an active port pair and an active deployment per destination are unique across every model.';

-- ---------------------------------------------------------------------------
-- 9. Auditoría (append-only lógico)
-- ---------------------------------------------------------------------------
CREATE TABLE audit_log (
    audit_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id     UUID REFERENCES project(project_id) ON DELETE SET NULL,
    actor_user_id  UUID REFERENCES user_account(user_id) ON DELETE SET NULL,
    command_id     UUID REFERENCES model_command(command_id) ON DELETE SET NULL,
    action         VARCHAR(128) NOT NULL,
    target_type    target_type,
    target_id      UUID,
    outcome        audit_outcome NOT NULL,
    reason         TEXT,
    metadata       JSONB,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_project_time ON audit_log(project_id, occurred_at);
CREATE INDEX idx_audit_actor_time    ON audit_log(actor_user_id, occurred_at);

-- ---------------------------------------------------------------------------
-- Índices de apoyo (recuperación, paneles de conflicto, versionado)
-- ---------------------------------------------------------------------------
CREATE INDEX idx_project_owner        ON project(owner_user_id);
CREATE INDEX idx_member_user          ON project_member(user_id);
CREATE INDEX idx_classifier_namespace ON classifier(namespace_id);
CREATE INDEX idx_attribute_classifier ON uml_attribute(classifier_id);
CREATE INDEX idx_operation_classifier ON uml_operation(classifier_id);
CREATE INDEX idx_parameter_operation  ON operation_parameter(operation_id);
CREATE INDEX idx_relationship_model   ON uml_relationship(model_id);
CREATE INDEX idx_relationship_end_rel ON relationship_end(relationship_id);
CREATE INDEX idx_relationship_end_cls ON relationship_end(classifier_id);
CREATE INDEX idx_constraint_model     ON uml_constraint(model_id);
CREATE INDEX idx_command_model_rev    ON model_command(model_id, base_revision);
CREATE INDEX idx_command_author       ON model_command(author_user_id);
CREATE INDEX idx_version_model_rev    ON model_version(model_id, revision DESC);
CREATE INDEX idx_tombstone_model      ON tombstone(model_id);
CREATE INDEX idx_conflict_model_state ON model_conflict(model_id, state);
CREATE INDEX idx_collision_model_name ON name_collision(model_id, name);
CREATE INDEX idx_proposal_model       ON ai_proposal(model_id, status);
CREATE INDEX idx_exchange_model       ON xmi_exchange(model_id, direction);
CREATE INDEX idx_generation_target_model ON generation_target(model_id, target_key);
CREATE INDEX idx_run_model            ON generation_run(model_id, state);
CREATE INDEX idx_run_target           ON generation_run(target_id, version_id, state);
CREATE INDEX idx_artifact_run         ON generated_artifact(run_id);
CREATE INDEX idx_verification_run     ON verification_result(run_id);
CREATE INDEX idx_deployment_run       ON deployment(run_id);
CREATE INDEX idx_deployment_state     ON deployment(state);
CREATE UNIQUE INDEX uq_deployment_active_target        ON deployment(target_id) WHERE state IN ('PENDING', 'RUNNING', 'UP', 'STOPPING');
CREATE UNIQUE INDEX uq_deployment_active_http_port     ON deployment(http_host_port) WHERE state IN ('PENDING', 'RUNNING', 'UP', 'STOPPING');
CREATE UNIQUE INDEX uq_deployment_active_postgres_port ON deployment(postgres_host_port) WHERE state IN ('PENDING', 'RUNNING', 'UP', 'STOPPING');