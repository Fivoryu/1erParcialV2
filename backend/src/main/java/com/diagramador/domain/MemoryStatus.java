package com.diagramador.domain;

/**
 * Lifecycle status of a diagram memory (T5/L43): vigente, en conflicto con otra sesión (both stay
 * consultable, CA-16) or explicitly replaced while keeping the predecessor distinguishable (CA-17).
 */
public enum MemoryStatus {
    ACTIVE,
    CONFLICTED,
    REPLACED
}
