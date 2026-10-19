package com.diagramador.assistant;

import java.util.List;

/**
 * Conversation-oriented AI provider used by the collaborative chat (T3 of
 * odd/tasks/chat-ia-colaborativo-web.md, L38). Unlike {@link AiAssistantProvider}, which is bound
 * to the plan-JSON prompt contract of the existing assistant flow, this route returns free text
 * and never proposes operations, so the two contracts stay independent.
 */
public interface ChatAssistantProvider {

    /**
     * Answers a conversation turn list (system context and history included by the caller) with
     * free text. Implementations throw {@link IllegalStateException} when disabled, mirroring
     * {@link AiAssistantProvider#draft(String, String)}.
     */
    String chat(List<Turn> turns);

    /**
     * The conversational system prompt the implementation prepends internally (T6/L45): the
     * context budget counts it as the instructions component without duplicating its text.
     */
    String systemPrompt();

    /**
     * One OpenAI-style message: role is "system", "user" or "assistant". An attachment turn
     * carries its image inline as base64 data (T8/L49 — the visual route); text-only turns keep
     * the two-argument factory.
     */
    record Turn(String role, String content, String imageBase64, String imageMime) {
        public Turn(String role, String content) {
            this(role, content, null, null);
        }

        public Turn(String role, String content, String imageBase64) {
            this(role, content, imageBase64, "image/jpeg");
        }
    }
}
