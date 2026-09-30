package com.diagramador.generation;

import com.diagramador.domain.GeneratedArtifact;
import com.diagramador.domain.GenerationRun;
import com.diagramador.domain.GenerationState;
import com.diagramador.domain.VerificationResult;
import com.diagramador.domain.VerificationState;
import com.diagramador.generation.GenerationArtifactBundleBuilder.ArtifactDescriptor;
import com.diagramador.generation.GenerationArtifactBundleBuilder.GeneratedArtifactBundle;
import com.diagramador.generation.GenerationArtifactBundleBuilder.GenerationIdentity;
import com.diagramador.persistence.GeneratedArtifactRepository;
import com.diagramador.persistence.GenerationRunRepository;
import com.diagramador.persistence.VerificationResultRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records honest per-run verification without changing the generation lifecycle state. */
@Service
public class GenerationVerificationService {

    public static final String ARTIFACT_SET_STRUCTURE = "artifact_set_structure";
    public static final String FLYWAY_MIGRATION_FILE = "flyway_migration_file";
    public static final String ARTIFACT_HASHES = "artifact_hashes";
    public static final String FROZEN_INPUT_DETERMINISM = "frozen_input_determinism";
    public static final String TARGET_SAFE_CONFIGURATION = "target_safe_configuration";

    public static final List<String> EXECUTED_CHECK_NAMES = List.of(
            ARTIFACT_SET_STRUCTURE,
            FLYWAY_MIGRATION_FILE,
            ARTIFACT_HASHES,
            FROZEN_INPUT_DETERMINISM,
            TARGET_SAFE_CONFIGURATION);

    public static final List<NotRunCheck> NOT_EXECUTED_CHECKS = List.of(
            new NotRunCheck("jpa_entities_and_relationships",
                    "Increment 2 does not inspect or validate generated JPA mappings per run."),
            new NotRunCheck("repositories",
                    "Increment 2 does not execute generated repository behavior tests per run."),
            new NotRunCheck("services_and_supported_operations",
                    "Increment 2 does not execute generated service or supported-operation behavior tests per run."),
            new NotRunCheck("rest_dto_openapi_behavior",
                    "Increment 2 does not execute HTTP or semantic OpenAPI contract checks per run."),
            new NotRunCheck("validation_and_error_handling",
                    "Increment 2 does not execute generated validation or error-handling behavior per run."),
            new NotRunCheck("security",
                    "Increment 2 does not execute a generated-backend security check per run."),
            new NotRunCheck("authorization",
                    "Increment 2 does not execute a generated-backend authorization check per run."),
            new NotRunCheck("audit",
                    "Increment 2 does not execute a generated-backend audit check per run."),
            new NotRunCheck("postgres_migration_execution",
                    "The migration file is inspected, but Increment 2 does not apply it to PostgreSQL per run."),
            new NotRunCheck("generated_backend_compilation",
                    "Increment 2 verification does not compile the generated backend per run."),
            new NotRunCheck("generated_backend_startup",
                    "Increment 2 verification does not start the generated backend per run."),
            new NotRunCheck("generated_backend_health",
                    "Increment 2 verification does not call a generated-backend health endpoint per run."),
            new NotRunCheck("generated_backend_contract_smoke",
                    "Increment 2 verification does not run the generated-backend HTTP contract smoke test per run."),
            new NotRunCheck("generated_backend_tests",
                    "Increment 2 verification does not run the generated project's unit or integration tests per run."),
            new NotRunCheck("docker_compose_execution",
                    "Increment 2 verification does not start the generated project's Docker Compose services per run."),
            new NotRunCheck("generated_backend_runtime_reproducibility",
                    "Increment 2 compares artifact-set hashes across two builds, but does not reproduce a generated backend in a clean runtime environment."));

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+\"[^\"\\r\\n]+\"\\s*\\(.*\\)\\s*$");
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile(
            "\\$\\{[A-Za-z_][A-Za-z0-9_]*}|\\{\\{[A-Za-z_][A-Za-z0-9_]*}}|<[^<>]+>");
    private static final Map<String, String> REQUIRED_LAYERS = Map.of(
            "entity", "domain/entities",
            "repository", "persistence/repositories",
            "service", "application/services",
            "controller", "API/controllers");

    private static final String STRUCTURE_TOOL = "generated artifact structure inspection";
    private static final String MIGRATION_TOOL = "generated migration content inspection";
    private static final String HASH_TOOL = "frozen artifact hash recomputation";
    private static final String DETERMINISM_TOOL = "frozen input artifact rebuild comparison";
    private static final String SAFETY_TOOL = "credential-shaped value inspection";
    private static final String NOT_RUN_TOOL = "Cycle 3 per-run check not executed";

    private final GenerationRunRepository runRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final VerificationResultRepository verificationRepository;
    private final GenerationArtifactBundleBuilder bundleBuilder;
    private final DomainModelIRMapper irMapper;

    public GenerationVerificationService(GenerationRunRepository runRepository,
            GeneratedArtifactRepository artifactRepository, VerificationResultRepository verificationRepository,
            GenerationArtifactBundleBuilder bundleBuilder, DomainModelIRMapper irMapper) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
        this.verificationRepository = Objects.requireNonNull(verificationRepository, "verificationRepository");
        this.bundleBuilder = Objects.requireNonNull(bundleBuilder, "bundleBuilder");
        this.irMapper = Objects.requireNonNull(irMapper, "irMapper");
    }

    /** Rebuilds checks from the frozen input, persists every result, and returns the run summary. */
    @Transactional
    public VerificationSummary verify(UUID runId, InputSnapshot snapshot) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(snapshot, "snapshot");
        GenerationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Generation run " + runId + " does not exist"));
        List<CheckEvaluation> evaluations = new ArrayList<>();

        if (run.getState() != GenerationState.SUCCEEDED) {
            for (String checkName : EXECUTED_CHECK_NAMES) {
                evaluations.add(evaluation(runId, checkName, VerificationState.NOT_RUN,
                        "Generation state is " + run.getState()
                                + "; verification requires a successfully generated artifact set.", NOT_RUN_TOOL));
            }
        } else {
            BuildContext context = buildContext(run, snapshot);
            if (context == null) {
                for (String checkName : EXECUTED_CHECK_NAMES) {
                    evaluations.add(evaluation(runId, checkName, VerificationState.FAILED,
                            "Frozen artifact reconstruction failed; this check could not be established.",
                            toolFor(checkName)));
                }
            } else {
                List<GeneratedArtifact> persistedArtifacts = artifactRepository.findByRunIdOrderByPathAsc(runId);
                evaluations.add(safely(runId, ARTIFACT_SET_STRUCTURE, STRUCTURE_TOOL,
                        () -> inspectStructure(runId, context.bundle(), persistedArtifacts)));
                evaluations.add(safely(runId, FLYWAY_MIGRATION_FILE, MIGRATION_TOOL,
                        () -> inspectMigration(runId, context.bundle())));
                evaluations.add(safely(runId, ARTIFACT_HASHES, HASH_TOOL,
                        () -> inspectHashes(run, runId, context.bundle())));
                evaluations.add(safely(runId, FROZEN_INPUT_DETERMINISM, DETERMINISM_TOOL,
                        () -> inspectDeterminism(runId, context)));
                evaluations.add(safely(runId, TARGET_SAFE_CONFIGURATION, SAFETY_TOOL,
                        () -> inspectTargetSafety(run, runId, context.bundle(), persistedArtifacts)));
            }
        }

        for (NotRunCheck check : NOT_EXECUTED_CHECKS) {
            evaluations.add(evaluation(runId, check.checkName(), VerificationState.NOT_RUN,
                    check.reason(), NOT_RUN_TOOL));
        }

        List<VerificationResult> saved = evaluations.stream()
                .map(result -> persist(run, result))
                .toList();
        verificationRepository.saveAllAndFlush(saved);
        List<VerificationResult> readBack = verificationRepository.findByRunIdOrderByCreatedAtAsc(runId);
        return summarize(run, readBack);
    }

    /** Reads verification rows for a run without changing its generation state. */
    @Transactional(readOnly = true)
    public VerificationSummary readSummary(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        GenerationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Generation run " + runId + " does not exist"));
        return summarize(run, verificationRepository.findByRunIdOrderByCreatedAtAsc(runId));
    }

    private BuildContext buildContext(GenerationRun run, InputSnapshot snapshot) {
        try {
            requireFrozenRun(run, snapshot);
            GenerationIdentity identity = bundleBuilder.identity(run, snapshot);
            DomainModelIR ir = irMapper.map(snapshot,
                    VersionedGenerationProfileConfiguration.primitiveTypes(run.getProfile().getConfiguration()));
            GeneratedArtifactBundle bundle = bundleBuilder.build(identity, snapshot, ir, VerificationState.NOT_RUN);
            return new BuildContext(identity, snapshot, ir, bundle);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void requireFrozenRun(GenerationRun run, InputSnapshot snapshot) {
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

    private CheckEvaluation inspectStructure(UUID runId, GeneratedArtifactBundle bundle,
            List<GeneratedArtifact> persistedArtifacts) {
        Map<String, String> files = bundle.files();
        Set<String> emittedPaths = new TreeSet<>(files.keySet());
        Set<String> completeManifestPaths = new TreeSet<>();
        for (ArtifactDescriptor descriptor : bundle.completeManifest()) {
            completeManifestPaths.add(descriptor.path());
            String content = files.get(descriptor.path());
            if (content == null || !descriptor.contentHash().equals(sha256(content.getBytes(StandardCharsets.UTF_8)))
                    || descriptor.sizeBytes() != content.getBytes(StandardCharsets.UTF_8).length) {
                return evaluation(runId, ARTIFACT_SET_STRUCTURE, VerificationState.FAILED,
                        "Structure evaluation executed; a complete-manifest entry does not match its emitted file.",
                        STRUCTURE_TOOL);
            }
        }
        Set<String> payloadPaths = bundle.payloadManifest().stream()
                .map(ArtifactDescriptor::path).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        Set<String> expectedPayloadPaths = new TreeSet<>(emittedPaths);
        expectedPayloadPaths.remove(GenerationArtifactBundleBuilder.PROVENANCE_PATH);
        if (completeManifestPaths.size() != bundle.completeManifest().size()
                || !completeManifestPaths.equals(emittedPaths) || !payloadPaths.equals(expectedPayloadPaths)) {
            return evaluation(runId, ARTIFACT_SET_STRUCTURE, VerificationState.FAILED,
                    "Structure evaluation executed; the manifest does not cover exactly the emitted paths.",
                    STRUCTURE_TOOL);
        }

        Map<String, ArtifactDescriptor> descriptors = new HashMap<>();
        bundle.completeManifest().forEach(descriptor -> descriptors.put(descriptor.path(), descriptor));
        Map<String, GeneratedArtifact> rowsByPath = new HashMap<>();
        persistedArtifacts.forEach(artifact -> rowsByPath.put(artifact.getPath(), artifact));
        for (Map.Entry<String, String> layer : REQUIRED_LAYERS.entrySet()) {
            boolean emitted = bundle.completeManifest().stream()
                    .anyMatch(descriptor -> layer.getKey().equals(descriptor.kind()));
            boolean persisted = persistedArtifacts.stream()
                    .anyMatch(artifact -> layer.getKey().equals(artifact.getKind()));
            if (!emitted || !persisted) {
                return evaluation(runId, ARTIFACT_SET_STRUCTURE, VerificationState.FAILED,
                        "Structure evaluation executed; missing required layer: " + layer.getValue()
                                + (emitted ? " (persisted artifact manifest is incomplete)."
                                        : " (no layer artifact was emitted)."),
                        STRUCTURE_TOOL);
            }
        }

        if (!rowsByPath.keySet().equals(emittedPaths) || rowsByPath.size() != persistedArtifacts.size()) {
            return evaluation(runId, ARTIFACT_SET_STRUCTURE, VerificationState.FAILED,
                    "Structure evaluation executed; persisted artifact paths do not exactly cover emitted paths.",
                    STRUCTURE_TOOL);
        }
        for (Map.Entry<String, ArtifactDescriptor> expected : descriptors.entrySet()) {
            GeneratedArtifact actual = rowsByPath.get(expected.getKey());
            ArtifactDescriptor descriptor = expected.getValue();
            if (actual == null || !descriptor.kind().equals(actual.getKind())
                    || !descriptor.contentHash().equals(actual.getContentHash())
                    || actual.getSizeBytes() == null || descriptor.sizeBytes() != actual.getSizeBytes()) {
                return evaluation(runId, ARTIFACT_SET_STRUCTURE, VerificationState.FAILED,
                        "Structure evaluation executed; persisted artifact metadata differs from the emitted manifest.",
                        STRUCTURE_TOOL);
            }
        }
        return evaluation(runId, ARTIFACT_SET_STRUCTURE, VerificationState.PASSED,
                "Structure evaluation executed; all four required layers are present and the manifest covers exactly "
                        + emittedPaths.size() + " emitted paths.",
                STRUCTURE_TOOL);
    }

    private CheckEvaluation inspectMigration(UUID runId, GeneratedArtifactBundle bundle) {
        String path = "src/main/resources/db/migration/V1__init.sql";
        String migration = bundle.files().get(path);
        MigrationInspection inspection = inspectMigrationSql(migration);
        if (!inspection.valid()) {
            return evaluation(runId, FLYWAY_MIGRATION_FILE, VerificationState.FAILED,
                    "Migration inspection executed; the generated Flyway migration is missing, empty, or malformed.",
                    MIGRATION_TOOL);
        }
        return evaluation(runId, FLYWAY_MIGRATION_FILE, VerificationState.PASSED,
                "Migration inspection executed; V1__init.sql is non-empty and contains "
                        + inspection.tableCount() + " complete CREATE TABLE statements with balanced SQL delimiters.",
                MIGRATION_TOOL);
    }

    private CheckEvaluation inspectHashes(GenerationRun run, UUID runId, GeneratedArtifactBundle bundle) {
        String recomputedSetHash = recomputeArtifactSetHash(bundle);
        String recomputedZipHash = sha256(bundle.zipBytes());
        boolean setMatches = recomputedSetHash.equals(run.getArtifactSetHash())
                && recomputedSetHash.equals(bundle.artifactSetHash());
        boolean zipMatches = recomputedZipHash.equals(run.getArtifactZipHash())
                && recomputedZipHash.equals(bundle.artifactZipHash());
        VerificationState state = setMatches && zipMatches ? VerificationState.PASSED : VerificationState.FAILED;
        return evaluation(runId, ARTIFACT_HASHES, state,
                "Hash evaluation executed; artifact_set_hash=" + (setMatches ? "MATCH" : "MISMATCH")
                        + ", artifact_zip_hash=" + (zipMatches ? "MATCH" : "MISMATCH") + ".",
                HASH_TOOL);
    }

    private CheckEvaluation inspectDeterminism(UUID runId, BuildContext context) {
        GeneratedArtifactBundle repeated = bundleBuilder.build(context.identity(), context.snapshot(), context.ir(),
                VerificationState.NOT_RUN);
        String firstSetHash = recomputeArtifactSetHash(context.bundle());
        String secondSetHash = recomputeArtifactSetHash(repeated);
        boolean reproduced = firstSetHash.equals(secondSetHash);
        return evaluation(runId, FROZEN_INPUT_DETERMINISM,
                reproduced ? VerificationState.PASSED : VerificationState.FAILED,
                "Determinism evaluation executed over two artifact builds from the same frozen input; set hash "
                        + (reproduced ? "reproduced." : "did not reproduce."),
                DETERMINISM_TOOL);
    }

    private CheckEvaluation inspectTargetSafety(GenerationRun run, UUID runId, GeneratedArtifactBundle bundle,
            List<GeneratedArtifact> persistedArtifacts) {
        boolean unsafeTargetReference = ConnectionConfigRefValidator
                .looksCredentialLike(run.getTarget().getConnectionConfigRef());
        boolean unsafeArtifact = bundle.files().entrySet().stream()
                .anyMatch(entry -> looksCredentialLike(entry.getKey()) || looksCredentialLike(entry.getValue()));
        boolean unsafeProvenance = looksCredentialLike(bundle.provenanceJson());
        boolean unsafeUri = persistedArtifacts.stream()
                .map(GeneratedArtifact::getUri)
                .filter(Objects::nonNull)
                .anyMatch(ConnectionConfigRefValidator::looksCredentialLike);
        boolean safeEvidence = !looksCredentialLike(evidence(runId,
                "Target-safe configuration evaluation executed; credential-shaped values are not recorded."));
        boolean safe = !unsafeTargetReference && !unsafeArtifact && !unsafeProvenance && !unsafeUri && safeEvidence;
        return evaluation(runId, TARGET_SAFE_CONFIGURATION,
                safe ? VerificationState.PASSED : VerificationState.FAILED,
                safe
                        ? "Target-safe configuration evaluation executed; no credential-shaped value was found in the target reference, artifact set, provenance, artifact locations, or evidence."
                        : "Target-safe configuration evaluation executed; credential-shaped data was detected but its value was not recorded.",
                SAFETY_TOOL);
    }

    private CheckEvaluation safely(UUID runId, String checkName, String toolName,
            Supplier<CheckEvaluation> evaluation) {
        try {
            return evaluation.get();
        } catch (RuntimeException exception) {
            return evaluation(runId, checkName, VerificationState.FAILED,
                    "Evaluation executed but could not establish a passing result; exception details were not recorded.",
                    toolName);
        }
    }

    private VerificationResult persist(GenerationRun run, CheckEvaluation evaluation) {
        OffsetDateTime now = OffsetDateTime.now();
        VerificationResult result = verificationRepository.findByRunIdAndCheckName(run.getId(), evaluation.checkName())
                .orElseGet(() -> VerificationResult.create(run, evaluation.checkName(), evaluation.state(),
                        evaluation.evidence(), null, evaluation.toolName(), now, now));
        result.setRun(run);
        result.setCheckName(evaluation.checkName());
        result.setState(evaluation.state());
        result.setEvidence(evaluation.evidence());
        result.setEvidenceHash(null);
        result.setToolName(evaluation.toolName());
        result.setStartedAt(now);
        result.setFinishedAt(now);
        return result;
    }

    private VerificationSummary summarize(GenerationRun run, List<VerificationResult> results) {
        VerificationState status;
        if (results.stream().anyMatch(result -> result.getState() == VerificationState.FAILED)) {
            status = VerificationState.FAILED;
        } else if (results.isEmpty()
                || results.stream().anyMatch(result -> result.getState() == VerificationState.NOT_RUN)) {
            status = VerificationState.NOT_RUN;
        } else {
            status = VerificationState.PASSED;
        }
        List<CheckSummary> checks = results.stream().map(result -> new CheckSummary(result.getId(),
                result.getCheckName(), result.getState(), result.getEvidence(), result.getEvidenceHash(),
                result.getToolName())).toList();
        boolean verifiedBackend = run.getState() == GenerationState.SUCCEEDED
                && status == VerificationState.PASSED
                && EXECUTED_CHECK_NAMES.stream().allMatch(name -> results.stream().anyMatch(result ->
                        name.equals(result.getCheckName()) && result.getState() == VerificationState.PASSED));
        return new VerificationSummary(run.getId(), run.getState(), status, verifiedBackend, checks);
    }

    private static CheckEvaluation evaluation(UUID runId, String checkName, VerificationState state,
            String details, String toolName) {
        String stateText = state == VerificationState.NOT_RUN ? "NOT_RUN: " : "";
        return new CheckEvaluation(checkName, state, evidence(runId, stateText + details), toolName);
    }

    private static String evidence(UUID runId, String details) {
        return "Run " + runId + ": " + details;
    }

    private static String recomputeArtifactSetHash(GeneratedArtifactBundle bundle) {
        Map<String, String> payloadFiles = new TreeMap<>(bundle.files());
        payloadFiles.remove(GenerationArtifactBundleBuilder.PROVENANCE_PATH);
        String projection = String.join("\n", payloadFiles.entrySet().stream()
                .map(entry -> entry.getKey() + "|" + sha256(entry.getValue().getBytes(StandardCharsets.UTF_8)))
                .toList());
        return sha256(projection.getBytes(StandardCharsets.UTF_8));
    }

    private static MigrationInspection inspectMigrationSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return new MigrationInspection(false, 0);
        }
        List<String> statements = new ArrayList<>();
        StringBuilder statement = new StringBuilder();
        int parentheses = 0;
        char quote = 0;
        boolean lineComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                    statement.append(' ');
                }
                continue;
            }
            if (quote != 0) {
                statement.append(current);
                if (current == quote) {
                    if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                        statement.append(sql.charAt(++index));
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
                statement.append(current);
            } else if (current == '(') {
                parentheses++;
                statement.append(current);
            } else if (current == ')') {
                if (--parentheses < 0) {
                    return new MigrationInspection(false, 0);
                }
                statement.append(current);
            } else if (current == ';' && parentheses == 0) {
                String complete = statement.toString().trim();
                if (!complete.isEmpty()) {
                    statements.add(complete);
                }
                statement.setLength(0);
            } else {
                statement.append(current);
            }
        }
        if (quote != 0 || parentheses != 0 || !statement.toString().isBlank()) {
            return new MigrationInspection(false, 0);
        }
        boolean allCompleteTables = !statements.isEmpty()
                && statements.stream().allMatch(value -> CREATE_TABLE.matcher(value).matches());
        return new MigrationInspection(allCompleteTables, allCompleteTables ? statements.size() : 0);
    }

    private static boolean looksCredentialLike(String value) {
        if (value == null) {
            return false;
        }
        String placeholderSafe = ENV_PLACEHOLDER.matcher(value).replaceAll("<ENV_PLACEHOLDER>");
        return ConnectionConfigRefValidator.looksCredentialLike(placeholderSafe);
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(64);
            for (byte digestByte : digest) {
                result.append(String.format("%02x", digestByte));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String toolFor(String checkName) {
        return switch (checkName) {
            case ARTIFACT_SET_STRUCTURE -> STRUCTURE_TOOL;
            case FLYWAY_MIGRATION_FILE -> MIGRATION_TOOL;
            case ARTIFACT_HASHES -> HASH_TOOL;
            case FROZEN_INPUT_DETERMINISM -> DETERMINISM_TOOL;
            case TARGET_SAFE_CONFIGURATION -> SAFETY_TOOL;
            default -> NOT_RUN_TOOL;
        };
    }

    public record NotRunCheck(String checkName, String reason) {
    }

    public record CheckSummary(UUID verificationId, String checkName, VerificationState state, String evidence,
            String evidenceHash, String toolName) {
    }

    public record VerificationSummary(UUID runId, GenerationState generationState, VerificationState status,
            boolean verifiedBackend, List<CheckSummary> checks) {
        public VerificationSummary {
            checks = List.copyOf(checks);
        }
    }

    private record CheckEvaluation(String checkName, VerificationState state, String evidence, String toolName) {
    }

    private record BuildContext(GenerationIdentity identity, InputSnapshot snapshot, DomainModelIR ir,
            GeneratedArtifactBundle bundle) {
    }

    private record MigrationInspection(boolean valid, int tableCount) {
    }
}
