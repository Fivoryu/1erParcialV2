package com.diagramador.api;

import com.diagramador.command.CommandResult;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.validation.ModelValidationApiService;
import com.diagramador.validation.ModelValidationReport;
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

@RestController
@RequestMapping(path = "/projects", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProjectController {

    private final ProjectApiService projectApiService;
    private final ModelValidationApiService modelValidationApiService;

    public ProjectController(ProjectApiService projectApiService,
            ModelValidationApiService modelValidationApiService) {
        this.projectApiService = projectApiService;
        this.modelValidationApiService = modelValidationApiService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProjectResource> createProject(@Valid @RequestBody CreateProjectRequest request) {
        ProjectResource project = projectApiService.createProject(request);
        return ResponseEntity.created(URI.create("/projects/" + project.id())).body(project);
    }

    @GetMapping
    public List<ProjectResource> listProjects() {
        return projectApiService.listProjects();
    }

    @GetMapping("/{id}")
    public ProjectResource getProject(@PathVariable("id") UUID id) {
        return projectApiService.getProject(id);
    }

    @GetMapping("/{id}/model")
    public ModelResource getModel(@PathVariable("id") UUID id) {
        return projectApiService.getModel(id);
    }

    @GetMapping("/{id}/model/validation")
    public ModelValidationReport getModelValidation(@PathVariable("id") UUID id) {
        return modelValidationApiService.validateModel(id);
    }

    @PostMapping(path = "/{id}/commands", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CommandResult> executeCommand(@PathVariable("id") UUID id,
            @RequestBody OperationEnvelope envelope) {
        return ResponseEntity.ok(projectApiService.executeCommand(id, envelope));
    }
}
