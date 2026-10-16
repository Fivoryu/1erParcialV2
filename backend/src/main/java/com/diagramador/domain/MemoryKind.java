package com.diagramador.domain;

/**
 * Kind of a diagram memory (T5/L43): an express team decision, or an interpretation that stays a
 * pending hypothesis and is never confirmed by model confidence alone (CA-14).
 */
public enum MemoryKind {
    DECISION,
    HYPOTHESIS
}
