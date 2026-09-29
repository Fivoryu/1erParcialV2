package com.diagramador.domain;

/**
 * PostgreSQL type remains {@code diagrammer.multiplicity_kind}; matching names are only needed on Hibernate's DDL path, which Flyway replaces.
 */
public enum MultiplicityKind {
    EXACTLY_ONE,
    ZERO_OR_ONE,
    ONE_OR_MANY,
    ZERO_OR_MANY,
    RANGE
}
