package com.diagramador.generation;

import com.diagramador.domain.GenerationRun;
import com.diagramador.domain.GenerationState;
import com.diagramador.domain.VerificationState;
import com.diagramador.generation.GenerationArtifactBundleBuilder.ArtifactDescriptor;
import com.diagramador.generation.GenerationArtifactBundleBuilder.GeneratedArtifactBundle;
import com.diagramador.generation.GenerationArtifactBundleBuilder.GenerationIdentity;
import com.diagramador.persistence.GeneratedArtifactRepository;
import com.diagramador.persistence.GenerationRunRepository;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds and persists deterministic artifacts for an existing frozen generation run. */
@Service
public class GenerationArtifactService {

    private final GenerationRunRepository runRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final GenerationArtifactBundleBuilder bundleBuilder;
    private final DomainModelIRMapper irMapper;

    public GenerationArtifactService(GenerationRunRepository runRepository,
            GeneratedArtifactRepository artifactRepository, GenerationArtifactBundleBuilder bundleBuilder,
            DomainModelIRMapper irMapper) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
        this.bundleBuilder = Objects.requireNonNull(bundleBuilder, "bundleBuilder");
        this.irMapper = Objects.requireNonNull(irMapper, "irMapper");
    }

    @Transactional
    public GeneratedArtifactBundle generate(UUID runId, InputSnapshot snapshot) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(snapshot, "snapshot");
        GenerationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Generation run " + runId + " does not exist"));
        validateFrozenRun(run, snapshot);
        if (run.getState() == GenerationState.FAILED) {
            throw new IllegalStateException("Cannot generate artifacts for a failed generation run");
        }

        GenerationIdentity identity = bundleBuilder.identity(run, snapshot);
        DomainModelIR ir = irMapper.map(snapshot,
                VersionedGenerationProfileConfiguration.primitiveTypes(run.getProfile().getConfiguration()));
        // Verification is a separate lifecycle stage; generation provenance must not change afterward.
        GeneratedArtifactBundle bundle = bundleBuilder.build(identity, snapshot, ir, VerificationState.NOT_RUN);
        for (ArtifactDescriptor artifact : bundle.completeManifest()) {
            int affected = artifactRepository.upsertForRun(runId, artifact.path(), artifact.kind(),
                    artifact.contentHash(), artifact.sizeBytes());
            if (affected != 1) {
                throw new IllegalStateException("Generated artifact row was not persisted for " + artifact.path());
            }
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (run.getStartedAt() == null) {
            run.setStartedAt(now);
        }
        run.setArtifactSetHash(bundle.artifactSetHash());
        run.setArtifactZipHash(bundle.artifactZipHash());
        run.setState(GenerationState.SUCCEEDED);
        run.setFinishedAt(now);
        runRepository.saveAndFlush(run);
        return bundle;
    }

    private void validateFrozenRun(GenerationRun run, InputSnapshot snapshot) {
        UUID modelId = run.getModel().getId();
        if (!modelId.equals(snapshot.modelId()) || !modelId.equals(run.getVersion().getModel().getId())
                || !run.getVersion().getId().equals(snapshot.versionId())
                || !run.getInputSnapshotHash().equals(snapshot.snapshotHash())) {
            throw new IllegalArgumentException("Generation run does not match the supplied frozen input snapshot");
        }
        if (!modelId.equals(run.getTarget().getModel().getId()) || run.getTargetConfigRevision() <= 0) {
            throw new IllegalArgumentException("Generation run target identity is inconsistent with its model");
        }
        if (!run.getTemplate().getProfile().getId().equals(run.getProfile().getId())) {
            throw new IllegalArgumentException("Generation run template does not belong to its frozen profile");
        }
    }
}
