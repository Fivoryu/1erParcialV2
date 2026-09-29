package com.diagramador.domain;

/**
 * PostgreSQL type remains {@code diagrammer.classifier_kind}; matching names are only needed on Hibernate's DDL path, which Flyway replaces.
 */
public enum ClassifierKind {
    CLASS,
    ABSTRACT_CLASS,
    INTERFACE,
    ENUMERATION,
    PACKAGE
}
