package com.diagramador.domain;

/**
 * Kind of a version-control commit. PostgreSQL type remains {@code diagrammer.vcs_commit_kind};
 * matching names are only needed on Hibernate's DDL path, which Flyway replaces.
 */
public enum VcsCommitKind {
    INITIAL,
    MANUAL,
    MERGE
}
