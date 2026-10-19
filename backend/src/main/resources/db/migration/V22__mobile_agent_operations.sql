-- Additive receipts only: no generated-domain or pre-existing table is changed.
CREATE TABLE diagrammer.agent_operation (
    operation_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES diagrammer.user_account(user_id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES diagrammer.project(project_id) ON DELETE CASCADE,
    deployment_id UUID NOT NULL REFERENCES diagrammer.deployment(deployment_id) ON DELETE CASCADE,
    contract_hash VARCHAR(64) NOT NULL CHECK (contract_hash ~ '^[a-f0-9]{64}$'),
    canonical_payload TEXT NOT NULL CHECK (octet_length(canonical_payload) <= 70000),
    state VARCHAR(32) NOT NULL CHECK (state IN ('awaiting_confirmation', 'dispatching', 'completed', 'unknown')),
    response_status INTEGER,
    response_body TEXT CHECK (octet_length(response_body) <= 1048576),
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_agent_operation_scope ON diagrammer.agent_operation(user_id, project_id, deployment_id, operation_id);
