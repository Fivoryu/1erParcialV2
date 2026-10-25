package com.diagramador.domain;

/**
 * State of a merge request. PostgreSQL type remains {@code diagrammer.vcs_merge_state};
 * matching names are only needed on Hibernate's DDL path, which Flyway replaces.
 */
public enum VcsMergeState {
    OPEN,
    MERGED,
    CLOSED
}
