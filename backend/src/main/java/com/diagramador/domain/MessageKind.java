package com.diagramador.domain;

/**
 * Who produced a chat message. HUMAN messages carry a {@code author_user_id}; AI messages (T3)
 * have none — the assistant is not a user account.
 */
public enum MessageKind {
    HUMAN,
    AI,
    /** A plan the assistant suggested to the group (V25); its state lives in {@link ChatProposal}. */
    PROPOSAL
}
