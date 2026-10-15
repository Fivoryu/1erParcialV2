-- T5 of odd/tasks/chat-ia-colaborativo-web.md (L43): diagram-scoped memory with states, sources
-- and replacement linkage. Memory lives on the DiagramView (the "diagrama" frontier, PD-02) and
-- is independent of chat history: deleting history never touches these rows (CA-20/CA-22).
CREATE TABLE diagrammer.diagram_memory (
    memory_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    view_id UUID NOT NULL REFERENCES diagrammer.diagram_view(view_id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('DECISION', 'HYPOTHESIS')),
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CONFLICTED', 'REPLACED')),
    content TEXT NOT NULL,
    source_session_id UUID REFERENCES diagrammer.chat_session(session_id) ON DELETE SET NULL,
    source_message_id UUID REFERENCES diagrammer.diagram_message(message_id) ON DELETE SET NULL,
    replaced_by UUID REFERENCES diagrammer.diagram_memory(memory_id) ON DELETE SET NULL,
    created_by_user_id UUID REFERENCES diagrammer.user_account(user_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_diagram_memory_view
    ON diagrammer.diagram_memory(view_id, created_at, memory_id);

-- Sources are consumed exactly once per successful extraction (CA-21): a forgotten memory's
-- source is never re-fed, so nothing silently returns as active.
ALTER TABLE diagrammer.diagram_message
    ADD COLUMN memory_extracted BOOLEAN NOT NULL DEFAULT FALSE;
