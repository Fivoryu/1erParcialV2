-- ---------------------------------------------------------------------------
-- Deployment of a generated backend, copied from the physical schema authority
-- ---------------------------------------------------------------------------
-- A deployment is one generated backend running on this machine. It is tied to
-- an exact generation run, never to the latest run of its destination, so the
-- bytes that ended up deployed are always the frozen artifact.
--
-- The reason the port pair and the active deployment per destination are unique
-- is that the ports live on the machine and not in the model: two destinations
-- of two different models would otherwise be able to take the same port.
CREATE TYPE deployment_state AS ENUM ('PENDING', 'RUNNING', 'UP', 'FAILED', 'STOPPING', 'STOPPED');

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
-- Support indexes declared by the physical schema authority
-- ---------------------------------------------------------------------------
CREATE INDEX idx_deployment_run       ON deployment(run_id);
CREATE INDEX idx_deployment_state     ON deployment(state);
CREATE UNIQUE INDEX uq_deployment_active_target        ON deployment(target_id) WHERE state IN ('PENDING', 'RUNNING', 'UP', 'STOPPING');
CREATE UNIQUE INDEX uq_deployment_active_http_port     ON deployment(http_host_port) WHERE state IN ('PENDING', 'RUNNING', 'UP', 'STOPPING');
CREATE UNIQUE INDEX uq_deployment_active_postgres_port ON deployment(postgres_host_port) WHERE state IN ('PENDING', 'RUNNING', 'UP', 'STOPPING');
