CREATE TABLE diagrammer.agent_conversation (
    conversation_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES diagrammer.user_account(user_id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES diagrammer.project(project_id) ON DELETE CASCADE,
    deployment_id UUID NOT NULL REFERENCES diagrammer.deployment(deployment_id) ON DELETE CASCADE,
    contract_hash VARCHAR(64) NOT NULL CHECK (contract_hash ~ '^[a-f0-9]{64}$'),
    title VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    next_sequence BIGINT NOT NULL DEFAULT 1 CHECK (next_sequence > 0)
);
CREATE INDEX idx_agent_conversation_scope ON diagrammer.agent_conversation(user_id, project_id, deployment_id, conversation_id);
CREATE TABLE diagrammer.agent_message (
    message_id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES diagrammer.agent_conversation(conversation_id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    role VARCHAR(16) NOT NULL CHECK (role IN ('user', 'assistant', 'tool')),
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    client_created_at VARCHAR(64),
    metadata JSONB,
    UNIQUE(conversation_id, sequence)
);
