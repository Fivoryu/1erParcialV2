-- T8 of odd/tasks/chat-ia-colaborativo-web.md (L49): image attachments for the session chat.
-- Bytes live in the database (academic scope), validated by magic bytes at upload, before any
-- inference (CA-31). An attachment is born bound to its session and links to the message that
-- carries it (nullable until posted). Deleting a session cascades its attachments.
CREATE TABLE diagrammer.chat_attachment (
    attachment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES diagrammer.chat_session(session_id) ON DELETE CASCADE,
    message_id UUID REFERENCES diagrammer.diagram_message(message_id) ON DELETE SET NULL,
    filename TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    data BYTEA NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    created_by_user_id UUID REFERENCES diagrammer.user_account(user_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_attachment_session
    ON diagrammer.chat_attachment(session_id, created_at, attachment_id);
