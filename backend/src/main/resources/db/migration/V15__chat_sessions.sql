-- T2 of odd/tasks/chat-ia-colaborativo-web.md (PD-02 contract in L33).
-- Sessions are the conversation unit; each belongs to a DiagramView (the "diagrama" frontier).
-- diagram_message gains the owning session and a kind, because AI answers (written by T3) have no
-- human author. Existing messages move into a per-model "General" session anchored on the default
-- view, which is what the legacy /projects/{id}/messages endpoints keep serving.

CREATE TABLE diagrammer.chat_session (
    session_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    view_id UUID NOT NULL REFERENCES diagrammer.diagram_view(view_id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    created_by_user_id UUID NOT NULL REFERENCES diagrammer.user_account(user_id) ON DELETE RESTRICT,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- At most one compatibility (default) session per view; normal sessions are unlimited.
    -- Expressed as a partial unique index because a table-level UNIQUE (view_id, is_default)
    -- would wrongly allow only one non-default session per view as well.
    CONSTRAINT uq_chat_session_default_view CHECK (is_default IS NOT NULL)
);

CREATE INDEX idx_chat_session_view
    ON diagrammer.chat_session(view_id, created_at, session_id);

CREATE UNIQUE INDEX uq_chat_session_default_view
    ON diagrammer.chat_session(view_id) WHERE is_default;

COMMENT ON TABLE diagrammer.chat_session IS
    'A conversation inside one DiagramView. Deleting a session''s history empties it; the row stays reusable (PD-06 L34).';

ALTER TABLE diagrammer.diagram_message
    ADD COLUMN session_id UUID,
    ADD COLUMN kind TEXT NOT NULL DEFAULT 'HUMAN';

-- One "General" session per model on its default view, created by the project owner. Models are
-- guaranteed a default view by ProjectCreateHandler; a defensive view insert keeps legacy models
-- reachable even if one was not.
INSERT INTO diagrammer.diagram_view (model_id, name, view_kind, is_default, created_at)
SELECT m.model_id, 'General', 'CLASS', TRUE, now()
FROM diagrammer.uml_model m
WHERE NOT EXISTS (
    SELECT 1 FROM diagrammer.diagram_view dv
    WHERE dv.model_id = m.model_id AND dv.is_default
);

INSERT INTO diagrammer.chat_session (view_id, title, created_by_user_id, is_default)
SELECT dv.view_id, 'General', p.owner_user_id, TRUE
FROM diagrammer.uml_model m
JOIN diagrammer.project p ON p.project_id = m.project_id
JOIN diagrammer.diagram_view dv ON dv.model_id = m.model_id AND dv.is_default;

UPDATE diagrammer.diagram_message dm
SET session_id = s.session_id
FROM diagrammer.chat_session s
JOIN diagrammer.diagram_view dv ON dv.view_id = s.view_id
JOIN diagrammer.uml_model m ON m.model_id = dv.model_id
WHERE dm.model_id = m.model_id AND s.is_default;

ALTER TABLE diagrammer.diagram_message
    ALTER COLUMN session_id SET NOT NULL,
    ALTER COLUMN author_user_id DROP NOT NULL,
    ADD CONSTRAINT fk_diagram_message_session
        FOREIGN KEY (session_id) REFERENCES diagrammer.chat_session(session_id) ON DELETE CASCADE;

ALTER TABLE diagrammer.diagram_message
    ADD CONSTRAINT ck_diagram_message_kind CHECK (kind IN ('HUMAN', 'AI'));

COMMENT ON COLUMN diagrammer.diagram_message.kind IS
    'HUMAN messages have an author_user_id; AI messages (T3) do not.';
