package com.diagramador.assistant;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/projects", produces = MediaType.APPLICATION_JSON_VALUE)
public class AssistantApplyController {

    private final AssistantApplyService assistantApplyService;

    public AssistantApplyController(AssistantApplyService assistantApplyService) {
        this.assistantApplyService = assistantApplyService;
    }

    @PostMapping(path = "/{projectId}/assistant/apply", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AssistantApplyResponse> apply(@PathVariable("projectId") UUID projectId,
            @Valid @RequestBody AssistantApplyRequest request) {
        return ResponseEntity.ok(assistantApplyService.apply(projectId, request));
    }
}
