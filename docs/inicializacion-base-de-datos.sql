-- ============================================================================
-- Diagramador colaborativo de clases UML 2.5
-- Script de inicialización de la base de datos — PostgreSQL
-- ============================================================================
--
-- Qué hace este script
--   Crea la base de datos, el rol de aplicación y todo el esquema `diagrammer`
--   que el backend Spring Boot necesita para arrancar: tipos enumerados,
--   tablas, claves, restricciones, índices e historial de migraciones.
--
-- Fuente (no es una reconstrucción manual)
--   Es la consolidación en un solo archivo de las migraciones Flyway que el
--   backend aplica realmente, en su orden de versión:
--     backend/src/main/resources/db/migration/V1__initial_schema.sql ... V19__cu14_layout_state.sql
--   La aplicación las ejecuta sola al arrancar (spring.flyway.enabled=true,
--   esquema `diagrammer`); este archivo sirve para levantarlas por psql o para
--   documentar el esquema físico en el informe.
--
-- Entorno
--   Motor        : PostgreSQL 16 (imagen postgres:16.14 en Docker Compose)
--   Base de datos: umldiagrammer
--   Rol          : umldiagrammer
--   Esquema      : diagrammer
--   Codificación : UTF-8, zona horaria TIMESTAMPTZ
--
-- Uso
--   psql -h localhost -U postgres -f inicializacion-base-de-datos.sql
--   (o dejar que Spring Boot ejecute las migraciones al arrancar)
--
-- Advertencia sobre credenciales
--   Las contraseñas de este archivo son las de desarrollo local definidas en
--   docker-compose.yml. En un entorno real deben sustituirse por secretos
--   inyectados por variables de entorno (DB_USERNAME / DB_PASSWORD).
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 0. Base de datos y rol de aplicación (ejecutar como superusuario)
-- ---------------------------------------------------------------------------
-- Omitir este bloque si la base y el rol ya existen (por ejemplo, cuando los
-- crea la imagen oficial de PostgreSQL con POSTGRES_DB / POSTGRES_USER).
--
-- CREATE ROLE umldiagrammer LOGIN PASSWORD 'umldiagrammer';
-- CREATE DATABASE umldiagrammer OWNER umldiagrammer ENCODING 'UTF8';
-- \connect umldiagrammer

-- ===========================================================================
-- Migración V1 — initial schema
-- ===========================================================================
-- CU-01 foundation: deliberately limited to the eight tables needed to create
-- an empty modeling project and its revision-zero history.
CREATE SCHEMA IF NOT EXISTS diagrammer;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
SET search_path TO diagrammer, public;

CREATE TYPE project_member_role AS ENUM ('OWNER', 'EDITOR', 'VIEWER');
CREATE TYPE validation_state AS ENUM ('VALID', 'INVALID', 'PENDING');
CREATE TYPE command_origin AS ENUM ('MANUAL', 'AI', 'VOICE', 'PHOTO', 'XMI', 'UNDO', 'REDO', 'RESOLUTION', 'ADMIN', 'OFFLINE', 'IMPORT');
CREATE TYPE authorization_classification AS ENUM ('AUTHORIZED_ONLINE', 'VERIFIED_PRE_REVOCATION', 'VERIFIED_POST_REVOCATION', 'AMBIGUOUS', 'NOT_APPLICABLE');
CREATE TYPE audit_outcome AS ENUM ('SUCCESS', 'REJECTED', 'FAILED', 'AMBIGUOUS');
CREATE TYPE target_type AS ENUM ('CLASSIFIER', 'ATTRIBUTE', 'OPERATION', 'PARAMETER', 'RELATIONSHIP', 'RELATIONSHIP_END', 'CONSTRAINT', 'DIAGRAM_VIEW');

CREATE TABLE user_account (
    user_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_subject VARCHAR(512) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_external_subject UNIQUE (external_subject),
    CONSTRAINT chk_user_status CHECK (status IN ('ACTIVE', 'DISABLED', 'REVOKED'))
);

CREATE TABLE project (
    project_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id UUID NOT NULL REFERENCES user_account(user_id) ON DELETE RESTRICT,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_project_owner ON project(owner_user_id);

-- Intentional CU-01 deviation from docs/puds/datos/02-esquema-fisico.sql:
-- active project names are unique per owner, case-insensitively, so the
-- database is the authoritative guard for the concurrent E1 race.
CREATE UNIQUE INDEX uq_project_owner_active_name_ci
    ON diagrammer.project (owner_user_id, lower(name))
    WHERE archived_at IS NULL;
COMMENT ON INDEX diagrammer.uq_project_owner_active_name_ci IS $comment$
Intentional CU-01 deviation from docs/puds/datos/02-esquema-fisico.sql:
case-insensitive active owner-name uniqueness is the E1 race guard. The
canonical schema has no UNIQUE constraint on project.name. The remaining
canonical tables are delivered by later versioned migrations.
$comment$;

CREATE TABLE project_member (
    project_id UUID NOT NULL REFERENCES project(project_id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES user_account(user_id) ON DELETE CASCADE,
    role project_member_role NOT NULL DEFAULT 'VIEWER',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT chk_member_status CHECK (status IN ('ACTIVE', 'REMOVED'))
);

CREATE INDEX idx_member_user ON project_member(user_id);

CREATE TABLE uml_model (
    model_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL UNIQUE REFERENCES project(project_id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    current_revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE diagram_view (
    view_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    view_kind VARCHAR(32) NOT NULL DEFAULT 'CLASS',
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE model_version (
    version_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    parent_revision BIGINT,
    state_hash CHAR(64) NOT NULL,
    validation_state validation_state NOT NULL DEFAULT 'PENDING',
    created_by_user_id UUID REFERENCES user_account(user_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_model_version_revision UNIQUE (model_id, revision)
);

CREATE INDEX idx_version_model_rev ON model_version(model_id, revision DESC);

CREATE TABLE model_command (
    command_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    author_user_id UUID REFERENCES user_account(user_id) ON DELETE SET NULL,
    base_revision BIGINT NOT NULL,
    result_revision BIGINT,
    operation VARCHAR(128) NOT NULL,
    target_type target_type,
    target_id UUID,
    payload JSONB,
    origin command_origin NOT NULL DEFAULT 'MANUAL',
    authorization_classification authorization_classification NOT NULL DEFAULT 'AUTHORIZED_ONLINE',
    inverse_of_command_id UUID REFERENCES model_command(command_id) ON DELETE SET NULL,
    causal_parent_operation_id UUID REFERENCES model_command(command_id) ON DELETE SET NULL,
    idempotency_key VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_command_result_revision CHECK (result_revision IS NULL OR result_revision > base_revision),
    CONSTRAINT uq_command_idempotency UNIQUE (model_id, idempotency_key)
);

CREATE INDEX idx_command_model_rev ON model_command(model_id, base_revision);
CREATE INDEX idx_command_author ON model_command(author_user_id);

CREATE TABLE audit_log (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID REFERENCES project(project_id) ON DELETE SET NULL,
    actor_user_id UUID REFERENCES user_account(user_id) ON DELETE SET NULL,
    command_id UUID REFERENCES model_command(command_id) ON DELETE SET NULL,
    action VARCHAR(128) NOT NULL,
    target_type target_type,
    target_id UUID,
    outcome audit_outcome NOT NULL,
    reason TEXT,
    metadata JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_project_time ON audit_log(project_id, occurred_at);
CREATE INDEX idx_audit_actor_time ON audit_log(actor_user_id, occurred_at);

/*
 * Deferred from the canonical 32-table script for later versioned migrations:
 * invitation; classifier, type_reference, uml_attribute, uml_operation,
 * operation_parameter, uml_relationship, relationship_end, uml_constraint;
 * element_geometry, connector_path; cascade_group, tombstone, model_conflict,
 * name_collision; ai_proposal, detected_element; xmi_exchange, xmi_loss_item;
 * generation_profile, template_version, generation_run, generated_artifact,
 * verification_result. V1 is immutable; corrections use a new migration.
 */

-- ===========================================================================
-- Migración V2 — cu03 permissions
-- ===========================================================================
-- CU-03 permission state. V1 remains immutable; this migration is forward-only.
SET search_path TO diagrammer, public;
ALTER TABLE project_member ADD COLUMN can_create_diagram BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE uml_model ADD CONSTRAINT uq_uml_model_project_model UNIQUE (project_id, model_id);
ALTER TABLE diagram_view ADD COLUMN project_id UUID, ADD COLUMN created_by_user_id UUID,
    ADD COLUMN diagram_admin_user_id UUID;
INSERT INTO project_member (project_id, user_id, role, status)
SELECT p.project_id, p.owner_user_id, 'OWNER', 'ACTIVE' FROM project p
WHERE NOT EXISTS (SELECT 1 FROM project_member pm
                  WHERE pm.project_id = p.project_id AND pm.user_id = p.owner_user_id);
UPDATE project_member pm SET role = 'OWNER', status = 'ACTIVE' FROM project p
WHERE pm.project_id = p.project_id AND pm.user_id = p.owner_user_id
  AND (pm.role <> 'OWNER' OR pm.status <> 'ACTIVE');
UPDATE diagram_view v SET project_id = m.project_id, created_by_user_id = p.owner_user_id,
    diagram_admin_user_id = p.owner_user_id FROM uml_model m JOIN project p ON p.project_id = m.project_id
WHERE v.model_id = m.model_id;
ALTER TABLE project_member ADD CONSTRAINT chk_project_owner_active
    CHECK (role <> 'OWNER' OR status = 'ACTIVE');
CREATE UNIQUE INDEX uq_project_member_active_owner ON project_member (project_id)
    WHERE role = 'OWNER' AND status = 'ACTIVE';
ALTER TABLE diagram_view
    ADD CONSTRAINT fk_diagram_view_project FOREIGN KEY (project_id) REFERENCES project(project_id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_diagram_view_project_model FOREIGN KEY (project_id, model_id)
        REFERENCES uml_model(project_id, model_id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_diagram_view_creator FOREIGN KEY (created_by_user_id)
        REFERENCES user_account(user_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_diagram_view_admin FOREIGN KEY (diagram_admin_user_id)
        REFERENCES user_account(user_id) ON DELETE SET NULL,
    ADD CONSTRAINT uq_diagram_view_project_view UNIQUE (project_id, view_id);
CREATE INDEX idx_diagram_view_project ON diagram_view (project_id, created_at, view_id);
CREATE OR REPLACE FUNCTION prevent_project_owner_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE project_owner UUID;
BEGIN
    IF TG_OP = 'DELETE' AND OLD.role::text = 'OWNER' AND OLD.status = 'ACTIVE' THEN
        RAISE EXCEPTION 'project OWNER cannot be removed or demoted';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.role::text = 'OWNER' AND OLD.status = 'ACTIVE'
       AND (NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.role::text <> 'OWNER'
            OR NEW.status <> 'ACTIVE') THEN
        RAISE EXCEPTION 'project OWNER cannot be removed or demoted';
    END IF;
    IF TG_OP <> 'DELETE' AND NEW.role::text = 'OWNER' AND NEW.status = 'ACTIVE' THEN
        SELECT owner_user_id INTO project_owner FROM diagrammer.project WHERE project_id = NEW.project_id;
        IF NEW.user_id IS DISTINCT FROM project_owner THEN
            RAISE EXCEPTION 'project OWNER must match project.owner_user_id';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_project_member_owner_immutable BEFORE INSERT OR UPDATE OR DELETE ON project_member
    FOR EACH ROW EXECUTE FUNCTION prevent_project_owner_change();
CREATE OR REPLACE FUNCTION prevent_project_owner_reference_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id THEN
        RAISE EXCEPTION 'project OWNER reference is immutable';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_project_owner_reference_immutable BEFORE UPDATE OF owner_user_id ON project
    FOR EACH ROW EXECUTE FUNCTION prevent_project_owner_reference_change();
CREATE TABLE diagram_collaborator (
    collaborator_id UUID PRIMARY KEY DEFAULT gen_random_uuid(), project_id UUID NOT NULL,
    view_id UUID NOT NULL, user_id UUID NOT NULL, access_level VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_collaborator_project_view FOREIGN KEY (project_id, view_id)
        REFERENCES diagram_view(project_id, view_id) ON DELETE CASCADE,
    CONSTRAINT fk_collaborator_project_user FOREIGN KEY (project_id, user_id)
        REFERENCES project_member(project_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_collaborator_user FOREIGN KEY (user_id) REFERENCES user_account(user_id) ON DELETE RESTRICT,
    CONSTRAINT uq_diagram_collaborator_grant UNIQUE (project_id, view_id, user_id),
    CONSTRAINT chk_collaborator_access CHECK (access_level IN ('VIEWER', 'EDITOR')),
    CONSTRAINT chk_collaborator_status CHECK (status IN ('ACTIVE', 'REMOVED'))
);
CREATE INDEX idx_collaborator_view_status ON diagram_collaborator (project_id, view_id, status, user_id);
CREATE TABLE project_permission_change (
    permission_change_id UUID PRIMARY KEY DEFAULT gen_random_uuid(), project_id UUID NOT NULL
        REFERENCES project(project_id) ON DELETE RESTRICT,
    actor_user_id UUID NOT NULL REFERENCES user_account(user_id) ON DELETE RESTRICT,
    target_user_id UUID NOT NULL REFERENCES user_account(user_id) ON DELETE RESTRICT,
    scope VARCHAR(16) NOT NULL DEFAULT 'PROJECT', before_role project_member_role, after_role project_member_role,
    before_status VARCHAR(32), after_status VARCHAR(32), before_can_create_diagram BOOLEAN,
    after_can_create_diagram BOOLEAN, operation VARCHAR(128) NOT NULL, operation_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128), request_fingerprint CHAR(64) NOT NULL, outcome audit_outcome NOT NULL,
    reason TEXT, response_status INTEGER, response_body JSONB, occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_project_permission_actor_scope FOREIGN KEY (project_id, actor_user_id)
        REFERENCES project_member(project_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT chk_project_permission_scope CHECK (scope = 'PROJECT'),
    CONSTRAINT chk_project_permission_response_status CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599),
    CONSTRAINT uq_project_permission_operation UNIQUE (project_id, actor_user_id, operation_id)
);
CREATE UNIQUE INDEX uq_project_permission_idempotency
    ON project_permission_change (project_id, actor_user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_project_permission_history ON project_permission_change (project_id, occurred_at, permission_change_id);
CREATE TABLE diagram_permission_change (
    permission_change_id UUID PRIMARY KEY DEFAULT gen_random_uuid(), project_id UUID NOT NULL, view_id UUID NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES user_account(user_id) ON DELETE RESTRICT,
    target_user_id UUID NOT NULL REFERENCES user_account(user_id) ON DELETE RESTRICT,
    scope VARCHAR(16) NOT NULL DEFAULT 'DIAGRAM', before_access_level VARCHAR(16), after_access_level VARCHAR(16),
    before_status VARCHAR(32), after_status VARCHAR(32), before_diagram_admin_user_id UUID
        REFERENCES user_account(user_id) ON DELETE RESTRICT, after_diagram_admin_user_id UUID
        REFERENCES user_account(user_id) ON DELETE RESTRICT, operation VARCHAR(128) NOT NULL,
    operation_id VARCHAR(128) NOT NULL, idempotency_key VARCHAR(128), request_fingerprint CHAR(64) NOT NULL,
    outcome audit_outcome NOT NULL, reason TEXT, response_status INTEGER, response_body JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_diagram_permission_scope FOREIGN KEY (project_id, view_id)
        REFERENCES diagram_view(project_id, view_id) ON DELETE RESTRICT,
    CONSTRAINT fk_diagram_permission_actor_scope FOREIGN KEY (project_id, actor_user_id)
        REFERENCES project_member(project_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT chk_diagram_permission_scope CHECK (scope = 'DIAGRAM'),
    CONSTRAINT chk_diagram_permission_before_access CHECK
        (before_access_level IS NULL OR before_access_level IN ('VIEWER', 'EDITOR')),
    CONSTRAINT chk_diagram_permission_after_access CHECK
        (after_access_level IS NULL OR after_access_level IN ('VIEWER', 'EDITOR')),
    CONSTRAINT chk_diagram_permission_response_status CHECK
        (response_status IS NULL OR response_status BETWEEN 100 AND 599),
    CONSTRAINT uq_diagram_permission_operation UNIQUE (project_id, view_id, actor_user_id, operation_id)
);
CREATE UNIQUE INDEX uq_diagram_permission_idempotency ON diagram_permission_change
    (project_id, view_id, actor_user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_diagram_permission_history
    ON diagram_permission_change (project_id, view_id, occurred_at, permission_change_id);
CREATE OR REPLACE FUNCTION reject_permission_history_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'permission history is append-only'; END; $$;
CREATE TRIGGER trg_project_permission_history_append_only BEFORE UPDATE OR DELETE ON project_permission_change
    FOR EACH ROW EXECUTE FUNCTION reject_permission_history_mutation();
CREATE TRIGGER trg_diagram_permission_history_append_only BEFORE UPDATE OR DELETE ON diagram_permission_change
    FOR EACH ROW EXECUTE FUNCTION reject_permission_history_mutation();

-- ===========================================================================
-- Migración V3 — cu09 text proposals
-- ===========================================================================
SET search_path TO diagrammer, public;
CREATE TABLE ai_text_proposal (
 proposal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(), project_id UUID NOT NULL REFERENCES project(project_id) ON DELETE RESTRICT,
 model_id UUID NOT NULL, author_user_id UUID NOT NULL REFERENCES user_account(user_id) ON DELETE RESTRICT, request_id UUID NOT NULL,
 kind VARCHAR(16) NOT NULL CHECK (kind = 'TEXT'), base_revision BIGINT NOT NULL CHECK (base_revision > 0),
 status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AMBIGUOUS','INVALID','UNAVAILABLE')),
 submitted_text TEXT, normalized_payload JSONB NOT NULL,
 confidence NUMERIC(3,2) CHECK (confidence IS NULL OR confidence BETWEEN 0.00 AND 1.00),
 provider_name VARCHAR(64) NOT NULL, provider_version VARCHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), raw_text_expires_at TIMESTAMPTZ NOT NULL,
 record_expires_at TIMESTAMPTZ NOT NULL, raw_text_redacted_at TIMESTAMPTZ,
 CONSTRAINT fk_ai_text_proposal_project_model FOREIGN KEY (project_id, model_id) REFERENCES uml_model(project_id, model_id) ON DELETE RESTRICT,
 CONSTRAINT chk_ai_text_proposal_payload CHECK (jsonb_typeof(normalized_payload) = 'object' AND octet_length(normalized_payload::text) <= 32768),
 CONSTRAINT chk_ai_text_proposal_input_size CHECK (submitted_text IS NULL OR octet_length(submitted_text) <= 4096),
 CONSTRAINT chk_ai_text_proposal_retention_order CHECK (raw_text_expires_at >= created_at AND record_expires_at >= raw_text_expires_at),
 CONSTRAINT chk_ai_text_proposal_confidence_scale CHECK (confidence IS NULL OR confidence = round(confidence, 2))
);
CREATE INDEX idx_ai_text_proposal_project_created ON ai_text_proposal(project_id, created_at DESC, proposal_id);
CREATE INDEX idx_ai_text_proposal_project_model_revision ON ai_text_proposal(project_id, model_id, base_revision, created_at DESC);
CREATE INDEX idx_ai_text_proposal_project_author_request ON ai_text_proposal(project_id, author_user_id, request_id, created_at DESC);
CREATE INDEX idx_ai_text_proposal_raw_text_retention ON ai_text_proposal(raw_text_expires_at) WHERE submitted_text IS NOT NULL;
CREATE INDEX idx_ai_text_proposal_record_retention ON ai_text_proposal(record_expires_at);

-- ===========================================================================
-- Migración V4 — cu10 semantic state
-- ===========================================================================
ALTER TABLE diagrammer.model_version
    ADD COLUMN semantic_state JSONB NOT NULL
        DEFAULT '{"elements": [], "packageIds": [], "relationships": []}'::jsonb;

-- ===========================================================================
-- Migración V5 — cu10 review sessions
-- ===========================================================================
CREATE TABLE diagrammer.cu10_review_session (
    session_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id UUID NOT NULL REFERENCES diagrammer.user_account(user_id),
    project_id UUID NOT NULL REFERENCES diagrammer.project(project_id),
    model_id UUID NOT NULL,
    proposal_id UUID NOT NULL REFERENCES diagrammer.ai_text_proposal(proposal_id),
    proposal_request_id UUID NOT NULL,
    base_revision BIGINT NOT NULL CHECK (base_revision > 0),
    alternative_id UUID,
    selected_operation_ids JSONB NOT NULL,
    scalar_corrections JSONB NOT NULL,
    correction_digest VARCHAR(64) NOT NULL,
    candidate_digest VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    server_credential VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    decision_action VARCHAR(8),
    result_status VARCHAR(16),
    result_revision BIGINT,
    result_code VARCHAR(128),
    result_reason TEXT,
    CONSTRAINT fk_cu10_review_model FOREIGN KEY (project_id, model_id)
        REFERENCES diagrammer.uml_model(project_id, model_id),
    CONSTRAINT chk_cu10_review_ids CHECK (jsonb_typeof(selected_operation_ids) = 'array'),
    CONSTRAINT chk_cu10_review_corrections CHECK (jsonb_typeof(scalar_corrections) = 'object')
);
CREATE UNIQUE INDEX uq_cu10_review_session_candidate
    ON diagrammer.cu10_review_session(actor_user_id, project_id, proposal_id, candidate_digest);
CREATE INDEX idx_cu10_review_session_expiry ON diagrammer.cu10_review_session(expires_at);

-- ===========================================================================
-- Migración V6 — cu11 voice proposals
-- ===========================================================================
SET search_path TO diagrammer, public;
ALTER TABLE ai_text_proposal DROP CONSTRAINT IF EXISTS ai_text_proposal_kind_check;
ALTER TABLE ai_text_proposal ADD CONSTRAINT chk_ai_text_proposal_kind CHECK (kind IN ('TEXT', 'VOICE'));

-- ===========================================================================
-- Migración V7 — cu12 photo proposals
-- ===========================================================================
SET search_path TO diagrammer, public;
ALTER TABLE ai_text_proposal DROP CONSTRAINT IF EXISTS chk_ai_text_proposal_kind;
ALTER TABLE ai_text_proposal ADD CONSTRAINT chk_ai_text_proposal_kind CHECK (kind IN ('TEXT', 'VOICE', 'PHOTO'));
CREATE UNIQUE INDEX uq_ai_photo_proposal_request ON ai_text_proposal(project_id, request_id) WHERE kind = 'PHOTO';

-- ===========================================================================
-- Migración V8 — cu13 participants
-- ===========================================================================
-- CU-13 participant invitations; forward-only extension of V7.
SET search_path TO diagrammer, public;
CREATE TYPE invitation_status AS ENUM ('PENDING','ACCEPTED','REVOKED','EXPIRED');
ALTER TABLE audit_log ADD COLUMN operation_id VARCHAR(128);
CREATE UNIQUE INDEX uq_audit_project_operation ON audit_log(project_id,operation_id) WHERE operation_id IS NOT NULL;
CREATE TABLE invitation(
 invitation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(), project_id UUID NOT NULL REFERENCES project(project_id) ON DELETE CASCADE,
 invited_by_user_id UUID NOT NULL REFERENCES user_account(user_id) ON DELETE RESTRICT,
 invitee_user_id UUID NOT NULL REFERENCES user_account(user_id) ON DELETE RESTRICT,
 proposed_role project_member_role NOT NULL, status invitation_status NOT NULL DEFAULT 'PENDING',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_invitation_pending_target ON invitation(project_id,invitee_user_id) WHERE status='PENDING';
CREATE INDEX idx_invitation_project_status ON invitation(project_id,status,created_at,invitation_id);

-- ===========================================================================
-- Migración V9 — cu14 model conflicts
-- ===========================================================================
SET search_path TO diagrammer, public;
CREATE TABLE model_conflict (
 conflict_id UUID PRIMARY KEY DEFAULT gen_random_uuid(), model_id UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
 operation_id VARCHAR(128) NOT NULL, author VARCHAR(512) NOT NULL, base_revision BIGINT NOT NULL, current_revision BIGINT NOT NULL,
 causal_parent_operation_id VARCHAR(128), idempotency_key VARCHAR(128) NOT NULL, request_fingerprint CHAR(64) NOT NULL,
 request_payload JSONB NOT NULL, reason VARCHAR(128) NOT NULL, derivation_marker VARCHAR(32) NOT NULL DEFAULT 'CU-15',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), CONSTRAINT uq_model_conflict_operation UNIQUE(model_id,operation_id)
);
CREATE UNIQUE INDEX uq_model_conflict_idempotency ON model_conflict(model_id,idempotency_key);
CREATE INDEX idx_model_conflict_model_time ON model_conflict(model_id,created_at,conflict_id);

-- ===========================================================================
-- Migración V10 — cu15 conflict lifecycle
-- ===========================================================================
SET search_path TO diagrammer, public;

ALTER TABLE model_conflict
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN captured_current_state JSONB,
    ADD COLUMN resolved_at TIMESTAMPTZ,
    ADD COLUMN resolution_operation_id VARCHAR(128),
    ADD COLUMN resolution_author VARCHAR(512),
    ADD COLUMN resolution_revision BIGINT,
    ADD COLUMN resolution_fingerprint CHAR(64),
    ADD CONSTRAINT ck_model_conflict_status CHECK (status IN ('OPEN', 'RESOLVED', 'REJECTED'));

CREATE INDEX idx_model_conflict_open_order
    ON model_conflict(model_id, status, created_at, conflict_id);

-- ===========================================================================
-- Migración V11 — cu16 xmi import
-- ===========================================================================
SET search_path TO diagrammer, public;

CREATE TABLE xmi_exchange (
    exchange_id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(project_id) ON DELETE CASCADE,
    model_id UUID NOT NULL REFERENCES uml_model(model_id) ON DELETE CASCADE,
    command_id UUID NOT NULL REFERENCES model_command(command_id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(128) NOT NULL,
    base_revision BIGINT NOT NULL,
    result_revision BIGINT NOT NULL,
    profile VARCHAR(64) NOT NULL,
    source_hash CHAR(64) NOT NULL,
    report JSONB NOT NULL,
    created_by_user_id UUID REFERENCES user_account(user_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_xmi_exchange_idempotency UNIQUE (model_id, idempotency_key)
);

CREATE INDEX idx_xmi_exchange_project_time ON xmi_exchange(project_id, created_at, exchange_id);

CREATE TABLE xmi_loss_item (
    loss_item_id UUID PRIMARY KEY,
    exchange_id UUID NOT NULL REFERENCES xmi_exchange(exchange_id) ON DELETE CASCADE,
    severity VARCHAR(16) NOT NULL,
    code VARCHAR(64) NOT NULL,
    message TEXT NOT NULL,
    source_path VARCHAR(512),
    source_id VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_xmi_loss_exchange_time ON xmi_loss_item(exchange_id, created_at, loss_item_id);

-- ===========================================================================
-- Migración V12 — authentication
-- ===========================================================================
ALTER TABLE diagrammer.user_account
    ADD COLUMN IF NOT EXISTS email VARCHAR(320),
    ADD COLUMN IF NOT EXISTS password_hash VARCHAR(100),
    ADD COLUMN IF NOT EXISTS role VARCHAR(32);

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_account_email_ci
    ON diagrammer.user_account (lower(email))
    WHERE email IS NOT NULL;

-- ===========================================================================
-- Migración V13 — generation foundation
-- ===========================================================================
CREATE TYPE diagrammer.generation_state AS ENUM ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED');

CREATE TABLE diagrammer.generation_target (
    target_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id              UUID NOT NULL REFERENCES diagrammer.uml_model(model_id) ON DELETE CASCADE,
    target_key            VARCHAR(128) NOT NULL,
    name                  VARCHAR(200) NOT NULL,
    database_engine       VARCHAR(32) NOT NULL DEFAULT 'POSTGRESQL',
    database_name         VARCHAR(200) NOT NULL,
    database_schema       VARCHAR(200) NOT NULL DEFAULT 'public',
    connection_config_ref VARCHAR(512),
    configuration_revision INT NOT NULL DEFAULT 1,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_generation_target_key UNIQUE (model_id, target_key),
    CONSTRAINT chk_generation_target_revision CHECK (configuration_revision > 0),
    CONSTRAINT chk_generation_target_engine CHECK (database_engine = 'POSTGRESQL')
);

COMMENT ON TABLE diagrammer.generation_target IS
    'Stable generated backend destination. Credentials remain external; connection_config_ref is opaque metadata only.';

CREATE TABLE diagrammer.generation_profile (
    profile_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id           UUID REFERENCES diagrammer.project(project_id) ON DELETE CASCADE,
    profile_key          VARCHAR(128) NOT NULL,
    name                 VARCHAR(200) NOT NULL,
    revision              INT NOT NULL DEFAULT 1,
    configuration         JSONB NOT NULL DEFAULT '{}',
    configuration_hash    CHAR(64) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_profile_key_revision UNIQUE (project_id, profile_key, revision),
    CONSTRAINT chk_profile_revision CHECK (revision > 0)
);

CREATE TABLE diagrammer.template_version (
    template_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id  UUID NOT NULL REFERENCES diagrammer.generation_profile(profile_id) ON DELETE CASCADE,
    label       VARCHAR(128) NOT NULL,
    revision    INT NOT NULL DEFAULT 1,
    content_hash CHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_template_profile_revision UNIQUE (profile_id, revision),
    CONSTRAINT chk_template_revision CHECK (revision > 0)
);

CREATE TABLE diagrammer.generation_run (
    run_id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id                 UUID NOT NULL REFERENCES diagrammer.uml_model(model_id) ON DELETE CASCADE,
    version_id               UUID NOT NULL REFERENCES diagrammer.model_version(version_id) ON DELETE CASCADE,
    version_revision         BIGINT NOT NULL,
    target_id                UUID NOT NULL REFERENCES diagrammer.generation_target(target_id) ON DELETE CASCADE,
    target_key               VARCHAR(128) NOT NULL,
    target_config_revision   INT NOT NULL,
    target_config_hash       CHAR(64) NOT NULL,
    profile_id               UUID NOT NULL REFERENCES diagrammer.generation_profile(profile_id) ON DELETE CASCADE,
    profile_revision         INT NOT NULL,
    profile_configuration_hash CHAR(64) NOT NULL,
    template_id              UUID NOT NULL REFERENCES diagrammer.template_version(template_id) ON DELETE CASCADE,
    template_revision        INT NOT NULL,
    template_content_hash    CHAR(64) NOT NULL,
    input_snapshot_hash      CHAR(64) NOT NULL,
    artifact_set_hash        CHAR(64),
    artifact_zip_hash        CHAR(64),
    artifact_zip_uri         VARCHAR(1024),
    state                    diagrammer.generation_state NOT NULL DEFAULT 'PENDING',
    started_at               TIMESTAMPTZ,
    finished_at              TIMESTAMPTZ,
    CONSTRAINT uq_run_determinism UNIQUE (target_id, target_config_revision, version_id, profile_id, template_id),
    CONSTRAINT chk_run_target_revision CHECK (target_config_revision > 0),
    CONSTRAINT chk_run_profile_revision CHECK (profile_revision > 0),
    CONSTRAINT chk_run_template_revision CHECK (template_revision > 0)
);

CREATE INDEX idx_generation_target_model ON diagrammer.generation_target(model_id, target_key);
CREATE INDEX idx_generation_profile_project ON diagrammer.generation_profile(project_id, profile_key, revision, profile_id);
CREATE INDEX idx_template_profile_revision ON diagrammer.template_version(profile_id, revision, template_id);
CREATE INDEX idx_generation_run_model_state ON diagrammer.generation_run(model_id, state, run_id);
CREATE INDEX idx_generation_run_target_version ON diagrammer.generation_run(target_id, version_id, state, run_id);

INSERT INTO diagrammer.generation_profile
    (profile_id, project_id, profile_key, name, revision, configuration, configuration_hash)
VALUES
    ('00000000-0000-0000-0000-000000000001', NULL, 'spring-boot-postgresql-v1',
     'Spring Boot PostgreSQL v1', 1,
     '{"databaseEngine":"POSTGRESQL","javaVersion":"21","springBootVersion":"3.4.5"}'::jsonb,
     'aa3b955b673e1923de85cd3a24decb9dc38df0a0205ad6ce306f984c321eda08');

INSERT INTO diagrammer.template_version
    (template_id, profile_id, label, revision, content_hash)
VALUES
    ('00000000-0000-0000-0000-000000000002',
     '00000000-0000-0000-0000-000000000001', 'spring-boot-postgresql-v1', 1,
     'dd4883fb74e092ea06da79e2713ba71db22181668815cc4ee7016e30b9a0f55a');

-- ===========================================================================
-- Migración V14 — allow initial proposal revision
-- ===========================================================================
ALTER TABLE diagrammer.ai_text_proposal
    DROP CONSTRAINT IF EXISTS ai_text_proposal_base_revision_check,
    DROP CONSTRAINT IF EXISTS chk_ai_text_proposal_base_revision;

ALTER TABLE diagrammer.ai_text_proposal
    ADD CONSTRAINT chk_ai_text_proposal_base_revision CHECK (base_revision >= 0);

-- ===========================================================================
-- Migración V15 — allow initial review revision
-- ===========================================================================
ALTER TABLE diagrammer.cu10_review_session
    DROP CONSTRAINT IF EXISTS cu10_review_session_base_revision_check;

ALTER TABLE diagrammer.cu10_review_session
    ADD CONSTRAINT cu10_review_session_base_revision_check CHECK (base_revision >= 0);

-- ===========================================================================
-- Migración V16 — generation artifacts
-- ===========================================================================
-- Generation ZIPs are kept in PostgreSQL for v1 delivery. The 16 MiB cap bounds row growth;
-- a future object-store migration can replace this column without changing the run contract.
ALTER TABLE diagrammer.generation_run
    ADD COLUMN artifact_zip BYTEA,
    ADD COLUMN failure_reason VARCHAR(4000),
    ADD CONSTRAINT chk_generation_run_artifact_zip_size
        CHECK (artifact_zip IS NULL OR octet_length(artifact_zip) <= 16777216);

COMMENT ON COLUMN diagrammer.generation_run.artifact_zip IS
    'Complete deterministic ZIP bytes, capped at 16 MiB for synchronous v1 delivery.';
COMMENT ON COLUMN diagrammer.generation_run.failure_reason IS
    'Auditable terminal failure reason; populated only when state is FAILED.';

-- ===========================================================================
-- Migración V17 — generation artifact store
-- ===========================================================================
ALTER TABLE diagrammer.generation_run
    ADD COLUMN artifact_zip_reference VARCHAR(2048);

COMMENT ON COLUMN diagrammer.generation_run.artifact_zip_reference IS
    'Opaque object-store reference for the artifact ZIP; null means the bytes are in artifact_zip.';

-- ===========================================================================
-- Migración V18 — deployment registry
-- ===========================================================================
CREATE TABLE diagrammer.deployment (
    deployment_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id     UUID NOT NULL REFERENCES diagrammer.project(project_id) ON DELETE CASCADE,
    run_id         UUID NOT NULL REFERENCES diagrammer.generation_run(run_id) ON DELETE CASCADE,
    name           VARCHAR(200) NOT NULL,
    domain_label   VARCHAR(200) NOT NULL,
    base_url       VARCHAR(2048) NOT NULL,
    descriptor_url VARCHAR(2048) NOT NULL,
    status         VARCHAR(32) NOT NULL DEFAULT 'REGISTERED',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_deployment_project_name UNIQUE (project_id, name),
    CONSTRAINT chk_deployment_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT chk_deployment_domain_label_not_blank CHECK (length(btrim(domain_label)) > 0),
    CONSTRAINT chk_deployment_status CHECK (status IN ('REGISTERED', 'DISABLED'))
);

COMMENT ON TABLE diagrammer.deployment IS
    'Project-scoped registry of generated backend deployments. Reachability is not verified here; status is platform-maintained only.';
COMMENT ON COLUMN diagrammer.deployment.deployment_id IS
    'Stable registry identity for one generated backend deployment.';
COMMENT ON COLUMN diagrammer.deployment.project_id IS
    'Owning project; deleting the project deletes its deployment registry rows.';
COMMENT ON COLUMN diagrammer.deployment.run_id IS
    'Generation run that produced the deployment. Frozen generation identities are expanded from this run at API read time and are not duplicated here.';
COMMENT ON COLUMN diagrammer.deployment.name IS
    'Unique human-readable deployment name within the project.';
COMMENT ON COLUMN diagrammer.deployment.domain_label IS
    'Human-readable domain label shown to clients.';
COMMENT ON COLUMN diagrammer.deployment.base_url IS
    'Absolute HTTP or HTTPS base URL for the generated backend; reachability is not verified during registration.';
COMMENT ON COLUMN diagrammer.deployment.descriptor_url IS
    'Absolute HTTP or HTTPS URL for the generated backend descriptor; reachability is not verified during registration.';
COMMENT ON COLUMN diagrammer.deployment.status IS
    'Platform-maintained registration status changed only by explicit operations; it is not a health signal.';
COMMENT ON COLUMN diagrammer.deployment.created_at IS
    'Time at which the registry row was created.';
COMMENT ON COLUMN diagrammer.deployment.updated_at IS
    'Time at which registry metadata or status was last explicitly changed.';

CREATE INDEX idx_deployment_project_name ON diagrammer.deployment(project_id, name, deployment_id);
CREATE INDEX idx_deployment_run ON diagrammer.deployment(run_id, deployment_id);

-- ===========================================================================
-- Migración V19 — cu14 layout state
-- ===========================================================================
ALTER TABLE diagrammer.model_version
    ADD COLUMN layout_state JSONB;

