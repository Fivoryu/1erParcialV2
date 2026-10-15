package com.diagramador.domain;

/**
 * Who produced a chat message. HUMAN messages carry a {@code author_user_id}; AI messages (T3)
 * have none — the assistant is not a user account.
 */
public enum MessageKind {
    HUMAN,
    AI
}
