package com.diagramador.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.diagramador.domain.GenerationState;
import com.diagramador.generation.GenerationDiagnostic;
import com.diagramador.generation.GenerationVerificationService.VerificationSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class GenerationResources {

    private GenerationResources() {
    }

    public record CreateGenerationTargetRequest(
            @NotBlank @Size(max = 128) String targetKey,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 32) String databaseEngine,
            @NotBlank @Size(max = 200) String databaseName,
            @Size(max = 200) String databaseSchema,
            @Size(max = 512) String connectionConfigRef) {
    }

    public record GenerationTargetResource(
            UUID targetId,
            UUID modelId,
            String targetKey,
            String name,
            String databaseEngine,
            String databaseName,
            String databaseSchema,
            String connectionConfigRef,
            int configurationRevision,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    public record CreateGenerationRequest(
            @NotNull UUID modelId,
            @NotNull UUID versionId,
            @NotNull UUID targetId,
            @NotNull UUID profileId,
            @NotNull UUID templateId,
            @NotBlank @Size(max = 256) String idempotencyKey) {
    }

    public record GenerationRunResource(
            UUID runId,
            UUID modelId,
            UUID versionId,
            long modelRevision,
            UUID targetId,
            String targetKey,
            int targetConfigRevision,
            UUID profileId,
            String profileKey,
            int profileRevision,
            UUID templateId,
            int templateRevision,
            GenerationState state,
            String inputSnapshotHash,
            String artifactSetHash,
            String artifactZipHash,
            String artifactZipUri,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            List<GenerationDiagnostic> diagnostics,
            VerificationSummary verification) {

        public GenerationRunResource {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OperationOutcomeResource(
            String outcome,
            String code,
            String message,
            String idempotencyKey,
            Object result) {
    }

    public record ArtifactDownload(byte[] bytes, String filename) {
    }
}
