package com.diagramador.domain;

/**
 * PostgreSQL type remains {@code diagrammer.project_member_role}; matching names are only needed on Hibernate's DDL path, which Flyway replaces.
 */
public enum ProjectMemberRole {
    OWNER,
    EDITOR,
    VIEWER
}
