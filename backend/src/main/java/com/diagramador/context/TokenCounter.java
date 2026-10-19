package com.diagramador.context;

/**
 * Counts tokens the way the runtime does (S10: the runtime's tokenizer is the authority).
 * Deterministic and available offline so budgets and thresholds are testable.
 */
public interface TokenCounter {

    int count(String text);
}
