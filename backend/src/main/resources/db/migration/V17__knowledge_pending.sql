-- T4 of odd/tasks/chat-ia-colaborativo-web.md (L41): accepted changes count as pending derived
-- processing until a later task consumes them (CA-19). The flag defaults to TRUE for every row
-- the command registry already persisted; no LLM is ever invoked by this change.
ALTER TABLE diagrammer.model_command
    ADD COLUMN knowledge_pending BOOLEAN NOT NULL DEFAULT TRUE;
