package com.diagramador.domain;

/**
 * PostgreSQL type remains {@code diagrammer.target_type}; matching names are only needed on Hibernate's DDL path, which Flyway replaces.
 */
public enum TargetType {
    CLASSIFIER,
    ATTRIBUTE,
    OPERATION,
    PARAMETER,
    RELATIONSHIP,
    RELATIONSHIP_END,
    CONSTRAINT,
    DIAGRAM_VIEW
}
