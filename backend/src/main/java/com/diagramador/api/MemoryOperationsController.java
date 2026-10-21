package com.diagramador.api;

import com.diagramador.api.MemoryOperationResources.MemoryWriteRequest;
import com.diagramador.api.MemoryOperationResources.SearchRequest;
import com.diagramador.api.MemoryOperationResources.SearchResponse;
import com.diagramador.collaboration.MemoryOperationsService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Additive read, search, save, and revision routes for structured web-chat operations. */
@RestController
public class MemoryOperationsController {

    private final MemoryOperationsService memoryOperationsService;

    public MemoryOperationsController(MemoryOperationsService memoryOperationsService) {
        this.memoryOperationsService = memoryOperationsService;
    }

    @GetMapping(path = "/projects/{projectId}/views/{viewId}/memories/{memoryId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DiagramMemoryResource read(@PathVariable("projectId") UUID projectId,
            @PathVariable("viewId") UUID viewId, @PathVariable("memoryId") UUID memoryId) {
        return memoryOperationsService.read(projectId, viewId, memoryId);
    }

    @PostMapping(path = "/projects/{projectId}/views/{viewId}/memories/search",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SearchResponse search(@PathVariable("projectId") UUID projectId, @PathVariable("viewId") UUID viewId,
            @RequestBody SearchRequest request) {
        return memoryOperationsService.search(projectId, viewId, request);
    }

    @PostMapping(path = "/projects/{projectId}/views/{viewId}/memories",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DiagramMemoryResource> save(@PathVariable("projectId") UUID projectId,
            @PathVariable("viewId") UUID viewId, @RequestBody MemoryWriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(memoryOperationsService.save(projectId, viewId, request));
    }

    @PutMapping(path = "/projects/{projectId}/views/{viewId}/memories/{memoryId}",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public DiagramMemoryResource revise(@PathVariable("projectId") UUID projectId,
            @PathVariable("viewId") UUID viewId, @PathVariable("memoryId") UUID memoryId,
            @RequestBody MemoryWriteRequest request) {
        return memoryOperationsService.revise(projectId, viewId, memoryId, request);
    }
}
