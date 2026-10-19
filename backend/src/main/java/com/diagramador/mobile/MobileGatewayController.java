package com.diagramador.mobile;

import static com.diagramador.mobile.MobileGatewayResources.BackendResource;
import static com.diagramador.mobile.MobileGatewayResources.ContractResource;
import static com.diagramador.mobile.MobileGatewayResources.OperationRequest;
import static com.diagramador.mobile.MobileGatewayResources.OperationResource;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The only public mobile route to generated data; the generated service stays off the public client path. */
@RestController
@RequestMapping(value = "/projects/{projectId}/mobile/backends", produces = MediaType.APPLICATION_JSON_VALUE)
public class MobileGatewayController {
    private final MobileGatewayService gatewayService;
    public MobileGatewayController(MobileGatewayService gatewayService) { this.gatewayService = gatewayService; }
    @GetMapping public List<BackendResource> list(@PathVariable UUID projectId) { return gatewayService.list(projectId); }
    @GetMapping("/{deploymentId}/contract") public ContractResource contract(@PathVariable UUID projectId,
            @PathVariable UUID deploymentId) { return gatewayService.contract(projectId, deploymentId); }
    @PostMapping(path = "/{deploymentId}/operations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public OperationResource operation(@PathVariable UUID projectId, @PathVariable UUID deploymentId,
            @Valid @RequestBody OperationRequest request) { return gatewayService.forward(projectId, deploymentId, request); }
}
