-- T6 of odd/tasks/chat-ia-colaborativo-web.md (L45): the compacted summary of a session's live
-- history, with the cursor of the last message it covers. The original messages stay untouched
-- (CA-25: sources remain recoverable; compaction never deletes or confirms anything).
CREATE TABLE diagrammer.chat_session_compaction (
    session_id UUID PRIMARY KEY REFERENCES diagrammer.chat_session(session_id) ON DELETE CASCADE,
    summary TEXT NOT NULL,
    up_to_message_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
