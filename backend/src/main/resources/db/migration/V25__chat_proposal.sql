-- T6 of odd/tasks/versionado-seguimiento-ia.md (S6): the AI as one more member of the chat. A
-- proposal is a chat message of kind PROPOSAL plus the plan it carries, kept so every member sees
-- the same preview and an editor can accept or reject it. Only an accepted proposal changes the
-- diagram, and it does so through the normal assistant apply path.

ALTER TABLE diagrammer.diagram_message
    DROP CONSTRAINT ck_diagram_message_kind;

ALTER TABLE diagrammer.diagram_message
    ADD CONSTRAINT ck_diagram_message_kind CHECK (kind IN ('HUMAN', 'AI', 'PROPOSAL'));

COMMENT ON COLUMN diagrammer.diagram_message.kind IS
    'HUMAN messages have an author_user_id; AI messages (T3) do not; PROPOSAL messages (V25) carry a chat_proposal.';

CREATE TABLE diagrammer.chat_proposal (
    proposal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID NOT NULL UNIQUE REFERENCES diagrammer.diagram_message(message_id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES diagrammer.chat_session(session_id) ON DELETE CASCADE,
    requested_by_user_id UUID NOT NULL REFERENCES diagrammer.user_account(user_id) ON DELETE RESTRICT,
    utterance TEXT NOT NULL,
    -- The plan exactly as the plan endpoint returned it (JSON array of {type, targetId, args}).
    operations TEXT NOT NULL,
    base_revision BIGINT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    decided_by_user_id UUID REFERENCES diagrammer.user_account(user_id) ON DELETE SET NULL,
    decided_at TIMESTAMPTZ,
    result_summary TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_chat_proposal_status CHECK (status IN ('PENDING', 'APPLYING', 'ACCEPTED', 'REJECTED', 'FAILED'))
);

CREATE INDEX idx_chat_proposal_session
    ON diagrammer.chat_proposal(session_id, created_at, proposal_id);

COMMENT ON TABLE diagrammer.chat_proposal IS
    'A plan suggested in a chat session. PENDING until an editor accepts (APPLYING while the plan runs through the command path) or rejects it.';
