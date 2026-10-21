package com.diagramador.api;

import java.util.List;
import java.util.UUID;

/** Request and response shapes for the additive web-chat memory operations API. */
public final class MemoryOperationResources {

    private MemoryOperationResources() {
    }

    public record MemoryWriteRequest(String kind, String content) {
    }

    public record SearchRequest(String query, Boolean includeAccessibleProjects, Integer limit) {
    }

    public record SearchSource(UUID projectId, String projectName, UUID viewId, String viewName) {
    }

    public record SearchHit(String type, String content, SearchSource source, UUID itemId, UUID memoryId,
            String kind, String status) {
    }

    public record SearchResponse(String query, List<SearchHit> results) {
        public SearchResponse {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }
}
