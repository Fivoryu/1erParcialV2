package com.diagramador.domain;

/**
 * PostgreSQL type remains {@code diagrammer.navigation_kind}; matching names are only needed on Hibernate's DDL path, which Flyway replaces.
 */
public enum NavigationKind {
    NAVIGABLE,
    NOT_NAVIGABLE,
    UNSPECIFIED
}
