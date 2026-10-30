-- ---------------------------------------------------------------------------
-- Aprobación de solicitudes de fusión.
-- Un Editor o Propietario distinto de quien abrió la solicitud la aprueba antes
-- de que el propietario la fusione. Una aprobación por (solicitud, usuario).
-- ---------------------------------------------------------------------------

CREATE TABLE diagrammer.vcs_merge_request_approval (
    merge_request_id UUID NOT NULL REFERENCES diagrammer.vcs_merge_request(merge_request_id) ON DELETE CASCADE,
    user_id          UUID NOT NULL REFERENCES diagrammer.user_account(user_id) ON DELETE CASCADE,
    approved_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (merge_request_id, user_id)
);

COMMENT ON TABLE diagrammer.vcs_merge_request_approval IS
    'Aprobaciones de revisores sobre una solicitud de fusión. Idempotente: aprobar dos veces conserva una sola fila.';
