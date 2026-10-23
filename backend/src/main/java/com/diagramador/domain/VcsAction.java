package com.diagramador.domain;

/**
 * Fine-grained action of a versioned diagram. PostgreSQL type remains
 * {@code diagrammer.vcs_action}; matching names are only needed on Hibernate's DDL path,
 * which Flyway replaces.
 *
 * <p>{@code CREATE} creates the diagram itself, {@code ADD} creates elements inside it,
 * {@code DELETE} removes elements, and the remaining actions gate the version-control
 * operations.
 */
public enum VcsAction {
    VIEW,
    CREATE,
    ADD,
    DELETE,
    COMMIT,
    BRANCH,
    MERGE,
    MANAGE_PERMISSIONS
}
