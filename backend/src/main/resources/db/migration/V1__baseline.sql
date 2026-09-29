-- Increment 1 subset of the physical schema owned by Flyway.
-- Source of truth: docs/puds/datos/02-esquema-fisico.sql (decision D5).
-- Table, column, constraint and COMMENT definitions are copied verbatim; only the
-- tables and native enum types this increment uses are included.

CREATE SCHEMA IF NOT EXISTS diagrammer;
SET search_path TO diagrammer, public;

-- Extensiones de uso habitual; pueden omitirse si la plataforma no las permite.
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS citext;     -- correo/identificadores comparables

-- ---------------------------------------------------------------------------
-- Enumeraciones (only the types used by the tables below)
-- ---------------------------------------------------------------------------
CREATE TYPE project_member_role AS ENUM ('OWNER', 'EDITOR', 'VIEWER');
CREATE TYPE classifier_kind AS ENUM ('CLASS', 'ABSTRACT_CLASS', 'INTERFACE', 'ENUMERATION', 'PACKAGE');
CREATE TYPE relationship_kind AS ENUM ('ASSOCIATION', 'AGGREGATION', 'COMPOSITION', 'GENERALIZATION', 'INTERFACE_REALIZATION', 'DEPENDENCY');
CREATE TYPE navigation_kind AS ENUM ('NAVIGABLE', 'NOT_NAVIGABLE', 'UNSPECIFIED');
CREATE TYPE multiplicity_kind AS ENUM ('EXACTLY_ONE', 'ZERO_OR_ONE', 'ONE_OR_MANY', 'ZERO_OR_MANY', 'RANGE');
CREATE TYPE severity_kind AS ENUM ('INFO', 'WARNING', 'ERROR');
CREATE TYPE target_type AS ENUM ('CLASSIFIER', 'ATTRIBUTE', 'OPERATION', 'PARAMETER', 'RELATIONSHIP', 'RELATIONSHIP_END', 'CONSTRAINT', 'DIAGRAM_VIEW');

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
-- Índices de apoyo incluidos en este incremento (subset del esquema físico)
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
