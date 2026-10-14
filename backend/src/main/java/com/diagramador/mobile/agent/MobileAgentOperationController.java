package com.diagramador.mobile.agent;

import com.diagramador.mobile.agent.MobileAgentOperationResources.Receipt;
import com.diagramador.mobile.agent.MobileAgentOperationResources.Request;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/projects/{projectId}/mobile/backends/{deploymentId}/agent/operations", produces = MediaType.APPLICATION_JSON_VALUE)
public class MobileAgentOperationController {
    private final MobileAgentOperationService service;
    public MobileAgentOperationController(MobileAgentOperationService service) { this.service = service; }
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Receipt submit(@PathVariable UUID projectId, @PathVariable UUID deploymentId, @Valid @RequestBody Request request) {
        return service.submit(projectId, deploymentId, request);
    }
    @GetMapping("/{operationId}")
    public Receipt lookup(@PathVariable UUID projectId, @PathVariable UUID deploymentId, @PathVariable UUID operationId,
            @RequestParam String contractHash) {
        return service.lookup(projectId, deploymentId, operationId, contractHash);
    }
}
