package com.diagramador.api;

import com.diagramador.domain.Deployment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class DeploymentResources {

    private DeploymentResources() {
    }

    /** An order names the run to deploy and nothing else: everything else is derived from that run. */
    public record CreateDeploymentRequest(@NotNull UUID runId) {
    }

    /** Who is taking the order. An opaque operator identity, never a credential. */
    public record ClaimDeploymentRequest(@NotBlank @Size(max = 128) String operatorId) {
    }

    /**
     * The outcome an operator reports.
     *
     * <p>{@code UP} carries the Compose project it started, {@code FAILED} carries a code and a reason, and
     * {@code STOPPED} says the stack and its volume are gone.
     */
    public record ReportDeploymentRequest(
            @NotBlank @Size(max = 32) String outcome,
            @Size(max = 128) String composeProject,
            @Size(max = 64) String failureCode,
            @Size(max = 1024) String failureMessage) {
    }

    public record DeploymentResource(
            UUID deploymentId,
            String state,
            UUID targetId,
            String targetKey,
            String targetName,
            UUID runId,
            int httpHostPort,
            int postgresHostPort,
            String composeProject,
            String operatorId,
            String failureCode,
            String failureMessage,
            OffsetDateTime requestedAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) {

        public static DeploymentResource from(Deployment deployment) {
            return new DeploymentResource(deployment.getId(), deployment.getState().name(),
                    deployment.getTarget().getId(), deployment.getTarget().getTargetKey(),
                    deployment.getTarget().getName(), deployment.getRun().getId(),
                    deployment.getHttpHostPort(), deployment.getPostgresHostPort(),
                    deployment.getComposeProject(), deployment.getOperatorId(), deployment.getFailureCode(),
                    deployment.getFailureMessage(), deployment.getRequestedAt(), deployment.getStartedAt(),
                    deployment.getFinishedAt());
        }
    }
}
