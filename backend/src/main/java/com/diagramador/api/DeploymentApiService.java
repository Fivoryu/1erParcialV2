package com.diagramador.api;

import static com.diagramador.api.DeploymentResources.ClaimDeploymentRequest;
import static com.diagramador.api.DeploymentResources.CreateDeploymentRequest;
import static com.diagramador.api.DeploymentResources.DeploymentResource;
import static com.diagramador.api.DeploymentResources.ReportDeploymentRequest;

import com.diagramador.domain.Deployment;
import com.diagramador.domain.DeploymentState;
import com.diagramador.domain.GenerationRun;
import com.diagramador.domain.GenerationState;
import com.diagramador.domain.UmlModel;
import com.diagramador.generation.ConnectionConfigRefValidator;
import com.diagramador.persistence.DeploymentRepository;
import com.diagramador.persistence.GenerationRunRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.UmlModelRepository;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders, claims, reports and stops deployments of generated backends.
 *
 * <p>The diagrammer never talks to Docker. It records what should be running and an operator on the host
 * carries it out, so this service owns three things and nothing else: which frozen run is being deployed, which
 * host port pair it holds, and the state the operator reported.
 *
 * <p>Errors use the HTTP status of {@link ApiException} rather than the {@code outcome} envelope of the
 * generation API. That envelope predates this surface; a new one states the problem in the status and keeps the
 * code so a client can branch on it.
 */
@Service
public class DeploymentApiService {

    /** The states in which a deployment holds its destination and its host port pair. */
    private static final List<DeploymentState> ACTIVE_STATES = List.of(
            DeploymentState.PENDING, DeploymentState.RUNNING, DeploymentState.UP, DeploymentState.STOPPING);

    public static final String OUTCOME_UP = "UP";
    public static final String OUTCOME_FAILED = "FAILED";
    public static final String OUTCOME_STOPPED = "STOPPED";

    /** The {@code 1900N}/{@code 2543N} convention of the repository, indexed from one. */
    private static final int HTTP_HOST_PORT_BASE = 19000;
    private static final int POSTGRES_HOST_PORT_BASE = 25432;
    private static final int MAX_PORT_INDEX = 10;

    /** A Compose project name, which is also what keeps a host path out of the table. */
    private static final Pattern COMPOSE_PROJECT = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9._:-]+");

    private final ProjectRepository projectRepository;
    private final UmlModelRepository modelRepository;
    private final GenerationRunRepository runRepository;
    private final DeploymentRepository deploymentRepository;

    public DeploymentApiService(ProjectRepository projectRepository, UmlModelRepository modelRepository,
            GenerationRunRepository runRepository, DeploymentRepository deploymentRepository) {
        this.projectRepository = projectRepository;
        this.modelRepository = modelRepository;
        this.runRepository = runRepository;
        this.deploymentRepository = deploymentRepository;
    }

    @Transactional(readOnly = true)
    public List<DeploymentResource> listDeployments(UUID projectId) {
        UmlModel model = requireModel(projectId);
        return deploymentRepository.findByModelIdOrderByRequestedAtDesc(model.getId()).stream()
                .map(DeploymentResource::from).toList();
    }

    /**
     * Orders a deployment of one frozen run.
     *
     * <p>Only a run that finished with both of its hashes is deployable: an incomplete run has no archive to
     * deploy, and saying so by name is the point.
     */
    @Transactional
    public DeploymentResource requestDeployment(UUID projectId, CreateDeploymentRequest request) {
        UmlModel model = requireModel(projectId);
        GenerationRun run = runRepository.findById(request.runId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND",
                        "Generation run was not found.", List.of(), null));
        if (!run.getModel().getId().equals(model.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "RUN_NOT_IN_PROJECT",
                    "The requested generation run does not belong to this project.", List.of(), null);
        }
        if (run.getState() != GenerationState.SUCCEEDED || run.getArtifactSetHash() == null
                || run.getArtifactZipHash() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "RUN_NOT_COMPLETE",
                    "Only a generation run that finished with an archive can be deployed.", List.of(
                            Map.of("runId", run.getId().toString(), "state", run.getState().name())), null);
        }
        deploymentRepository.findByTargetIdAndStateIn(run.getTarget().getId(), ACTIVE_STATES)
                .ifPresent(active -> {
                    throw new ApiException(HttpStatus.CONFLICT, "DEPLOYMENT_ALREADY_ACTIVE",
                            "This destination already has an active deployment; stop it first.", List.of(
                                    Map.of("deploymentId", active.getId().toString(),
                                            "state", active.getState().name())), null);
                });

        HostPorts ports = assignHostPorts();
        Deployment deployment = Deployment.request(run, ports.http(), ports.postgres(), OffsetDateTime.now());
        return DeploymentResource.from(deploymentRepository.saveAndFlush(deployment));
    }

    /**
     * Takes the order for one operator, and lets exactly one operator win.
     *
     * <p>The state is checked inside the {@code UPDATE}, so two operators that read the same pending order both
     * reach the database and only one changes a row. The loser is told who holds it, not merely that it lost.
     */
    @Transactional
    public DeploymentResource claimDeployment(UUID deploymentId, ClaimDeploymentRequest request) {
        requireDeployment(deploymentId);
        String operatorId = requireSafeOperatorId(request.operatorId());
        int claimed = deploymentRepository.claimIfPending(deploymentId, DeploymentState.PENDING,
                DeploymentState.RUNNING, operatorId, OffsetDateTime.now());
        Deployment deployment = requireDeployment(deploymentId);
        if (claimed != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "DEPLOYMENT_ALREADY_CLAIMED",
                    "This deployment is not pending any more; somebody else holds it or it already moved on.",
                    List.of(Map.of("state", deployment.getState().name(),
                            "operatorId", Objects.requireNonNullElse(deployment.getOperatorId(), "unknown"))), null);
        }
        return DeploymentResource.from(deployment);
    }

    /** Records what the operator observed, and rejects a reason that could carry a credential. */
    @Transactional
    public DeploymentResource reportDeployment(UUID deploymentId, ReportDeploymentRequest request) {
        Deployment deployment = requireDeployment(deploymentId);
        OffsetDateTime now = OffsetDateTime.now();
        try {
            switch (request.outcome()) {
                case OUTCOME_UP -> deployment.reportUp(requireComposeProject(request.composeProject()), now);
                case OUTCOME_STOPPED -> deployment.markStopped(now);
                case OUTCOME_FAILED -> deployment.reportFailure(requireSafeCode(request.failureCode()),
                        requireSafeFailureMessage(request.failureMessage()), now);
                default -> throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_DEPLOYMENT_OUTCOME",
                        "The reported outcome '" + request.outcome() + "' is not one of " + OUTCOME_UP + ", "
                                + OUTCOME_FAILED + ", " + OUTCOME_STOPPED + ".",
                        List.of(Map.of("outcome", request.outcome())), null);
            }
        } catch (IllegalStateException conflict) {
            throw new ApiException(HttpStatus.CONFLICT, "DEPLOYMENT_STATE_CONFLICT", conflict.getMessage(),
                    List.of(Map.of("deploymentId", deploymentId.toString(),
                            "state", deployment.getState().name(),
                            "requestedOutcome", request.outcome())), null);
        }
        return DeploymentResource.from(deploymentRepository.saveAndFlush(deployment));
    }

    /**
     * Asks for the stack to come down.
     *
     * <p>A deployment nobody claimed goes straight to stopped: no operator ever touched the machine, so there is
     * nothing to tear down.
     */
    @Transactional
    public DeploymentResource stopDeployment(UUID deploymentId) {
        Deployment deployment = requireDeployment(deploymentId);
        try {
            deployment.requestStop(OffsetDateTime.now());
        } catch (IllegalStateException conflict) {
            throw new ApiException(HttpStatus.CONFLICT, "DEPLOYMENT_STATE_CONFLICT", conflict.getMessage(),
                    List.of(Map.of("deploymentId", deploymentId.toString(),
                            "state", deployment.getState().name())), null);
        }
        return DeploymentResource.from(deploymentRepository.saveAndFlush(deployment));
    }

    private HostPorts assignHostPorts() {
        Set<Integer> taken = new HashSet<>(deploymentRepository.findActiveHostPorts(ACTIVE_STATES));
        for (int index = 1; index <= MAX_PORT_INDEX; index++) {
            int http = HTTP_HOST_PORT_BASE + index;
            int postgres = POSTGRES_HOST_PORT_BASE + index;
            if (!taken.contains(http) && !taken.contains(postgres)) {
                return new HostPorts(http, postgres);
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "NO_FREE_PORT_PAIR",
                "Every host port pair of the 1900N and 2543N convention is held by an active deployment.",
                List.of(Map.of("range", HTTP_HOST_PORT_BASE + 1 + "-" + (HTTP_HOST_PORT_BASE + MAX_PORT_INDEX))),
                null);
    }

    private record HostPorts(int http, int postgres) {
    }

    private String requireSafeOperatorId(String operatorId) {
        if (operatorId == null || !SAFE_CODE.matcher(operatorId).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSAFE_OPERATOR_ID",
                    "An operator id must match " + SAFE_CODE.pattern() + " and carry no credential.",
                    List.of(), null);
        }
        return operatorId;
    }

    private String requireSafeCode(String code) {
        if (code == null || !SAFE_CODE.matcher(code).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSAFE_FAILURE_CODE",
                    "A failure code must match " + SAFE_CODE.pattern() + ".", List.of(), null);
        }
        return code;
    }

    /** A Compose project name is a name, never a location: it may not walk out of the working directory. */
    private String requireComposeProject(String project) {
        if (project == null || !COMPOSE_PROJECT.matcher(project).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSAFE_COMPOSE_PROJECT",
                    "A Compose project name must match " + COMPOSE_PROJECT.pattern()
                            + "; it is a name and never a path.",
                    List.of(Map.of("composeProject", Objects.requireNonNullElse(project, ""))), null);
        }
        return project;
    }

    /**
     * A failure reason is stored, so it must not be able to carry a credential.
     *
     * <p>Rejecting it is deliberate rather than redacting it: the operator builds its own message and never has
     * to include a value from the environment, and a silently rewritten reason would hide that it did.
     */
    private String requireSafeFailureMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSAFE_FAILURE_MESSAGE",
                    "A failure message must not be blank; report the reason without any credential.",
                    List.of(), null);
        }
        if (ConnectionConfigRefValidator.looksCredentialLike(message)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSAFE_FAILURE_MESSAGE",
                    "The failure message carries something credential-shaped; report the reason without it.",
                    List.of(), null);
        }
        return message;
    }

    private Deployment requireDeployment(UUID deploymentId) {
        return deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DEPLOYMENT_NOT_FOUND",
                        "Deployment was not found.", List.of(), null));
    }

    private UmlModel requireModel(UUID projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Project was not found.", List.of(), null));
        return modelRepository.findByProjectId(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Model was not found.", List.of(), null));
    }
}
