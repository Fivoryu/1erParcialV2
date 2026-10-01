CREATE TYPE diagrammer.exchange_direction AS ENUM ('IMPORT', 'EXPORT');
CREATE TYPE diagrammer.exchange_status AS ENUM ('SUCCESS', 'PARTIAL', 'FAILED');

CREATE TABLE diagrammer.xmi_exchange (
    exchange_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id UUID NOT NULL REFERENCES diagrammer.uml_model(model_id) ON DELETE CASCADE,
    direction diagrammer.exchange_direction NOT NULL,
    profile VARCHAR(255) NOT NULL,
    model_revision BIGINT,
    checksum CHAR(64),
    status diagrammer.exchange_status NOT NULL DEFAULT 'SUCCESS',
    artifact_ref VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE diagrammer.xmi_loss_item (
    loss_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exchange_id UUID NOT NULL REFERENCES diagrammer.xmi_exchange(exchange_id) ON DELETE CASCADE,
    severity diagrammer.severity_kind NOT NULL DEFAULT 'WARNING',
    external_path VARCHAR(512),
    message TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_exchange_model ON diagrammer.xmi_exchange(model_id, direction);
