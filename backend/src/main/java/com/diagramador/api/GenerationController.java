package com.diagramador.api;

import static com.diagramador.api.GenerationResources.ArtifactDownload;
import static com.diagramador.api.GenerationResources.CreateGenerationRequest;
import static com.diagramador.api.GenerationResources.CreateGenerationTargetRequest;
import static com.diagramador.api.GenerationResources.GenerationProfileResource;
import static com.diagramador.api.GenerationResources.OperationOutcomeResource;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/projects")
public class GenerationController {

    private final GenerationApiService generationApiService;

    public GenerationController(GenerationApiService generationApiService) {
        this.generationApiService = generationApiService;
    }

    @PostMapping(path = "/{projectId}/generation-targets", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OperationOutcomeResource> createTarget(@PathVariable("projectId") UUID projectId,
            @Valid @RequestBody CreateGenerationTargetRequest request) {
        return ResponseEntity.ok(generationApiService.createTarget(projectId, request));
    }

    @GetMapping("/{projectId}/generation-targets")
    public List<GenerationResources.GenerationTargetResource> listTargets(
            @PathVariable("projectId") UUID projectId) {
        return generationApiService.listTargets(projectId);
    }

    @GetMapping("/{projectId}/generation-profiles")
    public List<GenerationProfileResource> listProfiles(@PathVariable("projectId") UUID projectId) {
        return generationApiService.listProfiles(projectId);
    }

    @PostMapping(path = "/{projectId}/generations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OperationOutcomeResource> createGeneration(@PathVariable("projectId") UUID projectId,
            @Valid @RequestBody CreateGenerationRequest request) {
        return ResponseEntity.ok(generationApiService.createGeneration(projectId, request));
    }

    @GetMapping("/{projectId}/generations/{runId}")
    public ResponseEntity<OperationOutcomeResource> getGeneration(@PathVariable("projectId") UUID projectId,
            @PathVariable("runId") UUID runId) {
        return ResponseEntity.ok(generationApiService.getGeneration(projectId, runId));
    }

    @GetMapping("/{projectId}/generations/{runId}/artifacts")
    public ResponseEntity<?> downloadArtifacts(@PathVariable("projectId") UUID projectId,
            @PathVariable("runId") UUID runId) {
        Object result = generationApiService.downloadArtifacts(projectId, runId);
        if (result instanceof ArtifactDownload download) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .contentLength(download.bytes().length)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(download.filename()).build().toString())
                    .body(download.bytes());
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result);
    }

    @PostMapping("/{projectId}/generations/{runId}/verification")
    public ResponseEntity<OperationOutcomeResource> verifyGeneration(@PathVariable("projectId") UUID projectId,
            @PathVariable("runId") UUID runId) {
        return ResponseEntity.ok(generationApiService.verifyGeneration(projectId, runId));
    }
}
