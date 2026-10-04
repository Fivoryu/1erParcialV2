package com.diagramador.assistant;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/projects", produces = MediaType.APPLICATION_JSON_VALUE)
public class AssistantPlanController {

    private final AssistantPlanService assistantPlanService;

    public AssistantPlanController(AssistantPlanService assistantPlanService) {
        this.assistantPlanService = assistantPlanService;
    }

    @PostMapping(path = "/{projectId}/assistant/plan", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AssistantPlanResponse> plan(@PathVariable("projectId") UUID projectId,
            @Valid @RequestBody AssistantPlanRequest request) {
        AssistantPlanResponse response = assistantPlanService.plan(projectId, request);
        if (response.diagnostics().stream()
                .anyMatch(diagnostic -> "PROVIDER_DISABLED".equals(diagnostic.code()))) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
        return ResponseEntity.ok(response);
    }
}
