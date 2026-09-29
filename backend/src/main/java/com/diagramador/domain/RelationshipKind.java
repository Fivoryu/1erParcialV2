package com.diagramador.domain;

/**
 * PostgreSQL type remains {@code diagrammer.relationship_kind}; matching names are only needed on Hibernate's DDL path, which Flyway replaces.
 */
public enum RelationshipKind {
    ASSOCIATION,
    AGGREGATION,
    COMPOSITION,
    GENERALIZATION,
    INTERFACE_REALIZATION,
    DEPENDENCY
}
