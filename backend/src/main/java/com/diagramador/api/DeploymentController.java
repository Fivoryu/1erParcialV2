package com.diagramador.api;

import static com.diagramador.api.DeploymentResources.ClaimDeploymentRequest;
import static com.diagramador.api.DeploymentResources.CreateDeploymentRequest;
import static com.diagramador.api.DeploymentResources.DeploymentResource;
import static com.diagramador.api.DeploymentResources.ReportDeploymentRequest;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deployments of generated backends: the product side orders and stops them, the operator side claims and
 * reports them.
 *
 * <p>The two halves share one resource. The editor orders a deployment of a run and shows its state; an
 * operator on the host claims the order, deploys, and reports back. Neither half can reach Docker: the API only
 * records what should be running.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class DeploymentController {

    private final DeploymentApiService deploymentApiService;

    public DeploymentController(DeploymentApiService deploymentApiService) {
        this.deploymentApiService = deploymentApiService;
    }

    @GetMapping("/projects/{projectId}/deployments")
    public List<DeploymentResource> listDeployments(@PathVariable("projectId") UUID projectId) {
        return deploymentApiService.listDeployments(projectId);
    }

    @PostMapping(path = "/projects/{projectId}/deployments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DeploymentResource> requestDeployment(@PathVariable("projectId") UUID projectId,
            @Valid @RequestBody CreateDeploymentRequest request) {
        DeploymentResource deployment = deploymentApiService.requestDeployment(projectId, request);
        return ResponseEntity.created(URI.create("/deployments/" + deployment.deploymentId())).body(deployment);
    }

    @PostMapping(path = "/deployments/{deploymentId}/claim", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DeploymentResource claimDeployment(@PathVariable("deploymentId") UUID deploymentId,
            @Valid @RequestBody ClaimDeploymentRequest request) {
        return deploymentApiService.claimDeployment(deploymentId, request);
    }

    @PostMapping(path = "/deployments/{deploymentId}/report", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DeploymentResource reportDeployment(@PathVariable("deploymentId") UUID deploymentId,
            @Valid @RequestBody ReportDeploymentRequest request) {
        return deploymentApiService.reportDeployment(deploymentId, request);
    }

    @PostMapping("/deployments/{deploymentId}/stop")
    public DeploymentResource stopDeployment(@PathVariable("deploymentId") UUID deploymentId) {
        return deploymentApiService.stopDeployment(deploymentId);
    }
}
