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
import java.util.Optional;
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
        GenerationRun run = requireRun(runId);
        if (run.getState() == GenerationState.FAILED) {
            throw new IllegalStateException("Cannot generate artifacts for a failed generation run");
        }

        GeneratedArtifactBundle bundle = buildBundle(run, snapshot);
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

    /** Reconstructs a ZIP only when the persisted run and complete artifact manifest agree. */
    @Transactional(readOnly = true)
    public Optional<GeneratedArtifactBundle> downloadComplete(UUID runId, InputSnapshot snapshot) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(snapshot, "snapshot");
        GenerationRun run = requireRun(runId);
        if (run.getState() != GenerationState.SUCCEEDED || run.getArtifactSetHash() == null
                || run.getArtifactZipHash() == null) {
            return Optional.empty();
        }

        GeneratedArtifactBundle bundle = buildBundle(run, snapshot);
        if (!run.getArtifactSetHash().equals(bundle.artifactSetHash())
                || !run.getArtifactZipHash().equals(bundle.artifactZipHash())
                || !persistedManifestMatches(runId, bundle)) {
            return Optional.empty();
        }
        return Optional.of(bundle);
    }

    private GenerationRun requireRun(UUID runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Generation run " + runId + " does not exist"));
    }

    private GeneratedArtifactBundle buildBundle(GenerationRun run, InputSnapshot snapshot) {
        validateFrozenRun(run, snapshot);
        GenerationIdentity identity = bundleBuilder.identity(run, snapshot);
        DomainModelIR ir = irMapper.map(snapshot,
                VersionedGenerationProfileConfiguration.primitiveTypes(run.getProfile().getConfiguration()));
        // Verification is a separate lifecycle stage; generation provenance must not change afterward.
        return bundleBuilder.build(identity, snapshot, ir, VerificationState.NOT_RUN);
    }

    private boolean persistedManifestMatches(UUID runId, GeneratedArtifactBundle bundle) {
        var persisted = artifactRepository.findByRunIdOrderByPathAsc(runId);
        if (persisted.size() != bundle.completeManifest().size()) {
            return false;
        }
        for (int index = 0; index < persisted.size(); index++) {
            var actual = persisted.get(index);
            ArtifactDescriptor expected = bundle.completeManifest().get(index);
            if (!expected.path().equals(actual.getPath()) || !expected.kind().equals(actual.getKind())
                    || !expected.contentHash().equals(actual.getContentHash())
                    || actual.getSizeBytes() == null || expected.sizeBytes() != actual.getSizeBytes()) {
                return false;
            }
        }
        return true;
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
