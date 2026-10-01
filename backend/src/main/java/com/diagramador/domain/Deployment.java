package com.diagramador.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Row of {@code diagrammer.deployment}: one generated backend running on this machine.
 *
 * <p>It points at an exact {@link GenerationRun}, never at the latest run of its destination, so what ended up
 * deployed is always the frozen artifact. It stores no secret and no host path: the operator keeps its working
 * directory and its `.env` locally, and only reports the Compose project name and, when something failed, a
 * sanitized reason.
 */
@Entity
@Table(name = "deployment", schema = "diagrammer")
public class Deployment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "deployment_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, updatable = false)
    private GenerationRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_id", nullable = false, updatable = false)
    private GenerationTarget target;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "state", nullable = false)
    private DeploymentState state = DeploymentState.PENDING;

    @Column(name = "http_host_port", nullable = false, updatable = false)
    private int httpHostPort;

    @Column(name = "postgres_host_port", nullable = false, updatable = false)
    private int postgresHostPort;

    @Column(name = "compose_project", length = 128)
    private String composeProject;

    @Column(name = "operator_id", length = 128)
    private String operatorId;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", length = 1024)
    private String failureMessage;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Deployment() {
    }

    /** Orders a deployment of one frozen run on a host port pair the caller has already reserved. */
    public static Deployment request(GenerationRun run, int httpHostPort, int postgresHostPort,
            OffsetDateTime now) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(now, "now");
        if (httpHostPort < 1024 || httpHostPort > 65535 || postgresHostPort < 1024 || postgresHostPort > 65535) {
            throw new IllegalArgumentException("A deployment port must be between 1024 and 65535");
        }
        if (httpHostPort == postgresHostPort) {
            throw new IllegalArgumentException("A deployment cannot use one port for both services");
        }
        Deployment deployment = new Deployment();
        deployment.run = run;
        deployment.target = run.getTarget();
        deployment.state = DeploymentState.PENDING;
        deployment.httpHostPort = httpHostPort;
        deployment.postgresHostPort = postgresHostPort;
        deployment.requestedAt = now;
        deployment.updatedAt = now;
        return deployment;
    }

    /**
     * Takes the order on behalf of one operator.
     *
     * <p>Whoever wins this call owns the deployment: the state moves to {@code RUNNING} and {@code operatorId}
     * names the holder, so a second operator that reads the same order sees it taken.
     */
    public void claim(String claimant, OffsetDateTime now) {
        requireText(claimant, "operator id");
        requireTransition(DeploymentState.RUNNING);
        this.state = DeploymentState.RUNNING;
        this.operatorId = claimant;
        this.startedAt = now;
        this.updatedAt = now;
    }

    /** Records that the stack is up and answers health. */
    public void reportUp(String project, OffsetDateTime now) {
        requireText(project, "compose project");
        requireTransition(DeploymentState.UP);
        this.state = DeploymentState.UP;
        this.composeProject = project;
        this.failureCode = null;
        this.failureMessage = null;
        this.finishedAt = now;
        this.updatedAt = now;
    }

    /**
     * Records a failed attempt. The reason must already be free of credentials: nothing on this path reads or
     * writes a secret, and {@code DeploymentApiTest} proves a credential-shaped reason is rejected.
     */
    public void reportFailure(String code, String message, OffsetDateTime now) {
        requireText(code, "failure code");
        requireText(message, "failure message");
        requireTransition(DeploymentState.FAILED);
        this.state = DeploymentState.FAILED;
        this.failureCode = code;
        this.failureMessage = message;
        this.finishedAt = now;
        this.updatedAt = now;
    }

    /** Asks for the stack to come down. */
    public void requestStop(OffsetDateTime now) {
        Objects.requireNonNull(now, "now");
        if (!STOPPABLE.contains(state)) {
            throw new IllegalStateException("A deployment in state " + state + " cannot be stopped");
        }
        if (state == DeploymentState.PENDING) {
            // No operator ever claimed it, so no stack exists and there is nothing to tear down.
            this.state = DeploymentState.STOPPED;
            this.finishedAt = now;
        } else {
            this.state = DeploymentState.STOPPING;
        }
        this.updatedAt = now;
    }

    /** Records that the stack and its volume are gone. */
    public void markStopped(OffsetDateTime now) {
        requireTransition(DeploymentState.STOPPED);
        this.state = DeploymentState.STOPPED;
        this.finishedAt = now;
        this.updatedAt = now;
    }

    private static final Set<DeploymentState> STOPPABLE =
            Set.of(DeploymentState.PENDING, DeploymentState.RUNNING, DeploymentState.UP, DeploymentState.FAILED);

    /** The whole state machine in one place, so an impossible move fails loudly instead of writing a row. */
    private static final Map<DeploymentState, Set<DeploymentState>> ALLOWED = Map.of(
            DeploymentState.PENDING, Set.of(DeploymentState.RUNNING, DeploymentState.STOPPED),
            DeploymentState.RUNNING, Set.of(DeploymentState.UP, DeploymentState.FAILED, DeploymentState.STOPPING),
            DeploymentState.UP, Set.of(DeploymentState.STOPPING),
            DeploymentState.FAILED, Set.of(DeploymentState.STOPPING),
            DeploymentState.STOPPING, Set.of(DeploymentState.STOPPED, DeploymentState.FAILED),
            DeploymentState.STOPPED, Set.of());

    private void requireTransition(DeploymentState target) {
        if (!ALLOWED.getOrDefault(state, Set.of()).contains(target)) {
            throw new IllegalStateException("A deployment in state " + state + " cannot move to " + target);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public UUID getId() {
        return id;
    }

    public GenerationRun getRun() {
        return run;
    }

    public GenerationTarget getTarget() {
        return target;
    }

    public DeploymentState getState() {
        return state;
    }

    public int getHttpHostPort() {
        return httpHostPort;
    }

    public int getPostgresHostPort() {
        return postgresHostPort;
    }

    public String getComposeProject() {
        return composeProject;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public OffsetDateTime getRequestedAt() {
        return requestedAt;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
