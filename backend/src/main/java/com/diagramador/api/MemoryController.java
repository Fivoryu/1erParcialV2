package com.diagramador.api;

import com.diagramador.collaboration.DiagramMemoryService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Diagram memory endpoints (T5/L43): extraction consumes pending sources once, recovery is
 * member-visible, and forgetting is OWNER-only and always separate from chat history.
 */
@RestController
@RequestMapping(produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
public class MemoryController {

    private final DiagramMemoryService memoryService;

    public MemoryController(DiagramMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @PostMapping("/projects/{projectId}/views/{viewId}/memories/extract")
    public DiagramMemoryService.ExtractionResult extract(@PathVariable("projectId") UUID projectId,
            @PathVariable("viewId") UUID viewId) {
        return memoryService.extract(projectId, viewId);
    }

    @GetMapping("/projects/{projectId}/views/{viewId}/memories")
    public List<DiagramMemoryResource> memories(@PathVariable("projectId") UUID projectId,
            @PathVariable("viewId") UUID viewId,
            @RequestParam(name = "status", required = false) String status) {
        return memoryService.memories(projectId, viewId, status);
    }

    @DeleteMapping("/projects/{projectId}/views/{viewId}/memories/{memoryId}")
    public Map<String, Integer> forgetOne(@PathVariable("projectId") UUID projectId,
            @PathVariable("viewId") UUID viewId, @PathVariable("memoryId") UUID memoryId) {
        return memoryService.forgetOne(projectId, viewId, memoryId);
    }

    @DeleteMapping("/projects/{projectId}/views/{viewId}/memories")
    public Map<String, Integer> forgetAll(@PathVariable("projectId") UUID projectId,
            @PathVariable("viewId") UUID viewId) {
        return memoryService.forgetAll(projectId, viewId);
    }
}
