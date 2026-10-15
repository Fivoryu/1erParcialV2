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
     * One OpenAI-style message: role is "system", "user" or "assistant".
     */
    record Turn(String role, String content) {
    }
}
