package com.diagramador.domain;

/**
 * PostgreSQL type remains {@code diagrammer.severity_kind}; matching names are only needed on Hibernate's DDL path, which Flyway replaces.
 */
public enum SeverityKind {
    INFO,
    WARNING,
    ERROR
}
