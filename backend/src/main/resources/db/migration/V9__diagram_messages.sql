CREATE TABLE diagrammer.diagram_message (
    message_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id UUID NOT NULL REFERENCES diagrammer.uml_model(model_id) ON DELETE CASCADE,
    author_user_id UUID NOT NULL REFERENCES diagrammer.user_account(user_id) ON DELETE RESTRICT,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_diagram_message_model
    ON diagrammer.diagram_message(model_id, created_at, message_id);

COMMENT ON TABLE diagrammer.diagram_message IS
    'Revoking a user''s access never erases messages they posted while they had it.';
