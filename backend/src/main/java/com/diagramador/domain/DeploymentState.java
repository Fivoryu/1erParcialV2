package com.diagramador.domain;

/**
 * Lifecycle of one deployment of a generated backend.
 *
 * <p>{@code PENDING} is an order no operator has claimed yet, {@code RUNNING} is an operator holding it,
 * {@code UP} and {@code FAILED} are the two outcomes of an attempt, {@code STOPPING} is a stop order an
 * operator still has to carry out, and {@code STOPPED} is the result.
 *
 * <p>{@link #isActive()} lists exactly the states in which a deployment holds its destination and its host
 * port pair. The partial unique indexes of {@code diagrammer.deployment} use the same set, so a change here
 * has to change the migration too; {@code DeploymentSchemaTest} fails if they drift apart.
 */
public enum DeploymentState {
    PENDING,
    RUNNING,
    UP,
    FAILED,
    STOPPING,
    STOPPED;

    /** True while this deployment holds its destination identity and its host port pair. */
    public boolean isActive() {
        return this == PENDING || this == RUNNING || this == UP || this == STOPPING;
    }

    /** True when this deployment has reached an outcome and holds nothing. */
    public boolean isTerminal() {
        return this == FAILED || this == STOPPED;
    }
}
