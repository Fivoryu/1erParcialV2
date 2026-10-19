-- T3 of odd/tasks/chat-ia-colaborativo-web.md (L38): AI answers link back to the question they
-- answer. The link is informational (the conversation lives in its session); deleting the
-- question keeps the answer, so the FK nulls out instead of cascading.
ALTER TABLE diagrammer.diagram_message
    ADD COLUMN reply_to_message_id UUID REFERENCES diagrammer.diagram_message(message_id) ON DELETE SET NULL;
