package com.diagramador.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.diagramador.domain.GenerationProfile;
import com.diagramador.domain.GenerationRun;
import com.diagramador.domain.GenerationState;
import com.diagramador.domain.GenerationTarget;
import com.diagramador.domain.ModelVersion;
import com.diagramador.domain.Project;
import com.diagramador.domain.TemplateVersion;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.VerificationResult;
import com.diagramador.domain.VerificationState;
import com.diagramador.generation.DomainModelIR.TypeDeclaration;
import com.diagramador.generation.GenerationDiagnostic;
import com.diagramador.generation.InputSnapshot.AttributeElement;
import com.diagramador.generation.InputSnapshot.ClassifierElement;
import com.diagramador.generation.InputSnapshot.OperationElement;
import com.diagramador.generation.InputSnapshot.ParameterElement;
import com.diagramador.generation.InputSnapshot.RelationshipElement;
import com.diagramador.persistence.GenerationProfileRepository;
import com.diagramador.persistence.GenerationRunRepository;
import com.diagramador.persistence.GenerationTargetRepository;
import com.diagramador.persistence.ModelVersionRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.TemplateVersionRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.VerificationResultRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns immutable generation identities and the pre-generation compatibility gate. */
@Service
public class GenerationIdentityService {

    public static final String V1_PROFILE_KEY = "spring-boot-postgresql";
    public static final String DIAGNOSTIC_CHECK_NAME = "generation-diagnostics";
    private static final String V1_PROFILE_NAME = "Spring Boot PostgreSQL";
    private static final String V1_PROFILE_LABEL = "Spring Boot PostgreSQL V1";
    private static final String V1_TEMPLATE_CONTENT = "spring-boot-postgresql-template-v1";
    private static final int MAX_IDENTITY_RETRIES = 5;

    private final GenerationTargetRepository targetRepository;
    private final GenerationProfileRepository profileRepository;
    private final TemplateVersionRepository templateRepository;
    private final GenerationRunRepository runRepository;
    private final VerificationResultRepository verificationRepository;
    private final UmlModelRepository modelRepository;
    private final ProjectRepository projectRepository;
    private final ModelVersionRepository versionRepository;
    private final InputSnapshotFactory snapshotFactory;
    private final DomainModelIRMapper irMapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public GenerationIdentityService(GenerationTargetRepository targetRepository,
            GenerationProfileRepository profileRepository, TemplateVersionRepository templateRepository,
            GenerationRunRepository runRepository, VerificationResultRepository verificationRepository,
            UmlModelRepository modelRepository, ProjectRepository projectRepository,
            ModelVersionRepository versionRepository, InputSnapshotFactory snapshotFactory,
            DomainModelIRMapper irMapper, ObjectMapper objectMapper, TransactionTemplate transactionTemplate) {
        this.targetRepository = targetRepository;
        this.profileRepository = profileRepository;
        this.templateRepository = templateRepository;
        this.runRepository = runRepository;
        this.verificationRepository = verificationRepository;
        this.modelRepository = modelRepository;
        this.projectRepository = projectRepository;
        this.versionRepository = versionRepository;
        this.snapshotFactory = snapshotFactory;
        this.irMapper = irMapper;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /** Provisions the unchanged V1 profile or returns the existing exact revision. */
    public GenerationProfile provisionV1Profile(UUID projectId) {
        return provisionProfile(projectId, VersionedGenerationProfileConfiguration.v1());
    }

    /** Evaluates a model version against the static V1 subset without persisting generation identities or runs. */
    @Transactional(readOnly = true)
    public java.util.Optional<GenerationReadiness> preflight(UUID projectId, UUID versionId) {
        return transactionTemplate.execute(status -> {
            UmlModel model = modelRepository.findByProjectId(projectId).orElse(null);
            ModelVersion version = versionRepository.findById(versionId).orElse(null);
            if (model == null || version == null || !version.getModel().getId().equals(model.getId())) {
                return java.util.Optional.empty();
            }

            JsonNode configuration = VersionedGenerationProfileConfiguration.v1();
            InputSnapshot snapshot = snapshotFactory.create(version);
            Map<String, ProvisionalGenerationProfile.TypeMapping> primitiveTypes =
                    VersionedGenerationProfileConfiguration.primitiveTypes(configuration);
            DomainModelIR ir = irMapper.map(snapshot, primitiveTypes);
            List<GenerationDiagnostic> diagnostics = profileSubsetDiagnostics(configuration, snapshot, ir)
                    .stream().distinct().toList();
            boolean ready = diagnostics.stream().noneMatch(GenerationDiagnostic::blocking);
            return java.util.Optional.of(new GenerationReadiness(version.getId(), V1_PROFILE_LABEL, ready,
                    diagnostics));
        });
    }

    /** Creates a new immutable revision only when the canonical non-secret configuration changes. */
    public GenerationProfile provisionProfile(UUID projectId, JsonNode configuration) {
        Objects.requireNonNull(projectId, "projectId");
        JsonNode canonicalConfiguration = VersionedGenerationProfileConfiguration.canonicalize(
                Objects.requireNonNull(configuration, "configuration"));
        validateNonSecretConfiguration(canonicalConfiguration, "configuration");
        String configurationHash = VersionedGenerationProfileConfiguration.hash(canonicalConfiguration, objectMapper);

        for (int attempt = 0; attempt < MAX_IDENTITY_RETRIES; attempt++) {
            try {
                return transactionTemplate.execute(status -> provisionProfileInTransaction(projectId,
                        canonicalConfiguration, configurationHash));
            } catch (DataIntegrityViolationException lostRace) {
                GenerationProfile latest = latestProfile(projectId);
                if (latest != null && latest.getConfigurationHash().equals(configurationHash)) {
                    return latest;
                }
                if (attempt + 1 == MAX_IDENTITY_RETRIES) {
                    throw lostRace;
                }
            }
        }
        throw new IllegalStateException("Profile revision could not be provisioned after concurrent retries");
    }

    /** Provisions one template revision tied to the exact immutable profile revision. */
    public TemplateVersion provisionTemplate(UUID profileId, String label, String templateContent) {
        Objects.requireNonNull(profileId, "profileId");
        requireText(label, "template label");
        Objects.requireNonNull(templateContent, "templateContent");
        if (ConnectionConfigRefValidator.looksCredentialLike(templateContent)) {
            throw new IllegalArgumentException("Template content must not contain credentials or secret-bearing values");
        }
        String contentHash = InputSnapshotFactory.sha256(templateContent);

        for (int attempt = 0; attempt < MAX_IDENTITY_RETRIES; attempt++) {
            try {
                return transactionTemplate.execute(status -> provisionTemplateInTransaction(profileId, label,
                        contentHash));
            } catch (DataIntegrityViolationException lostRace) {
                TemplateVersion latest = latestTemplate(profileId);
                if (latest != null && latest.getContentHash().equals(contentHash)) {
                    return latest;
                }
                if (attempt + 1 == MAX_IDENTITY_RETRIES) {
                    throw lostRace;
                }
            }
        }
        throw new IllegalStateException("Template revision could not be provisioned after concurrent retries");
    }

    public TemplateVersion provisionV1Template(UUID profileId) {
        return provisionTemplate(profileId, "V1", V1_TEMPLATE_CONTENT);
    }

    /** Creates an independently addressable target at its first configuration revision. */
    public GenerationTarget createTarget(UUID modelId, String targetKey, TargetConfiguration configuration) {
        Objects.requireNonNull(modelId, "modelId");
        requireText(targetKey, "target_key");
        TargetConfiguration normalized = validateAndNormalize(configuration);
        rejectCredentialLikeMetadata("target_key", targetKey);

        try {
            return transactionTemplate.execute(status -> {
                UmlModel model = modelRepository.findById(modelId)
                        .orElseThrow(() -> new IllegalArgumentException("Model " + modelId + " does not exist"));
                if (targetRepository.findByModelIdAndTargetKey(modelId, targetKey).isPresent()) {
                    throw duplicateTargetKey(modelId, targetKey);
                }
                GenerationTarget target = GenerationTarget.create(model, targetKey, normalized.name(),
                        normalized.databaseEngine(), normalized.databaseName(), normalized.databaseSchema(),
                        normalized.connectionConfigRef());
                return targetRepository.saveAndFlush(target);
            });
        } catch (DataIntegrityViolationException lostRace) {
            if (targetRepository.findByModelIdAndTargetKey(modelId, targetKey).isPresent()) {
                throw duplicateTargetKey(modelId, targetKey);
            }
            throw lostRace;
        }
    }

    /** Lists every independent destination belonging to one model in stable key order. */
    public List<GenerationTarget> listTargets(UUID modelId) {
        return transactionTemplate.execute(status -> {
            if (!modelRepository.existsById(modelId)) {
                throw new IllegalArgumentException("Model " + modelId + " does not exist");
            }
            return List.copyOf(targetRepository.findByModelIdOrderByTargetKeyAsc(modelId));
        });
    }

    /** Updates destination metadata as a new per-target configuration revision. */
    public GenerationTarget updateTargetConfiguration(UUID targetId, TargetConfiguration configuration) {
        Objects.requireNonNull(targetId, "targetId");
        TargetConfiguration normalized = validateAndNormalize(configuration);
        return transactionTemplate.execute(status -> {
            GenerationTarget target = targetRepository.findByIdForUpdate(targetId)
                    .orElseThrow(() -> new IllegalArgumentException("Generation target " + targetId + " does not exist"));
            if (sameConfiguration(target, normalized)) {
                return target;
            }
            if (target.getConfigurationRevision() == Integer.MAX_VALUE) {
                throw new IllegalStateException("target configuration_revision cannot be incremented beyond Integer.MAX_VALUE");
            }
            setTargetConfiguration(target, normalized);
            target.setConfigurationRevision(target.getConfigurationRevision() + 1);
            target.setUpdatedAt(OffsetDateTime.now());
            return targetRepository.saveAndFlush(target);
        });
    }

    /** Freezes all run identities and records blocking IR diagnostics as a failed, non-ready run. */
    public GenerationRunStart startRun(UUID targetId, UUID versionId, UUID profileId, UUID templateId) {
        try {
            return transactionTemplate.execute(status -> startRunInTransaction(targetId, versionId, profileId,
                    templateId));
        } catch (DataIntegrityViolationException lostRace) {
            GenerationTarget target = targetRepository.findById(targetId).orElse(null);
            ModelVersion version = versionRepository.findById(versionId).orElse(null);
            GenerationProfile profile = profileRepository.findById(profileId).orElse(null);
            TemplateVersion template = templateRepository.findById(templateId).orElse(null);
            if (target != null && version != null && profile != null && template != null) {
                GenerationRun existing = existingRun(target, version, profile, template);
                if (existing != null) {
                    return new GenerationRunStart(existing, diagnosticsFor(existing));
                }
            }
            throw lostRace;
        }
    }

    /** Reads diagnostics persisted for a run without creating or changing its identity. */
    public List<GenerationDiagnostic> readDiagnostics(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return transactionTemplate.execute(status -> {
            GenerationRun run = runRepository.findById(runId)
                    .orElseThrow(() -> new IllegalArgumentException("Generation run " + runId + " does not exist"));
            return diagnosticsFor(run);
        });
    }

    private GenerationProfile provisionProfileInTransaction(UUID projectId, JsonNode configuration,
            String configurationHash) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project " + projectId + " does not exist"));
        GenerationProfile latest = profileRepository
                .findFirstByProjectIdAndProfileKeyOrderByRevisionDesc(projectId, V1_PROFILE_KEY).orElse(null);
        if (latest != null && latest.getConfigurationHash().equals(configurationHash)) {
            return latest;
        }
        GenerationProfile profile = GenerationProfile.create(project, V1_PROFILE_KEY, V1_PROFILE_NAME,
                latest == null ? 1 : latest.getRevision() + 1, configuration, configurationHash);
        return profileRepository.saveAndFlush(profile);
    }

    private TemplateVersion provisionTemplateInTransaction(UUID profileId, String label, String contentHash) {
        GenerationProfile profile = profileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Generation profile " + profileId + " does not exist"));
        TemplateVersion latest = templateRepository.findFirstByProfileIdOrderByRevisionDesc(profileId).orElse(null);
        if (latest != null && latest.getContentHash().equals(contentHash)) {
            return latest;
        }
        TemplateVersion template = TemplateVersion.create(profile, label,
                latest == null ? 1 : latest.getRevision() + 1, contentHash);
        return templateRepository.saveAndFlush(template);
    }

    private GenerationRunStart startRunInTransaction(UUID targetId, UUID versionId, UUID profileId,
            UUID templateId) {
        GenerationTarget target = targetRepository.findByIdForUpdate(targetId)
                .orElseThrow(() -> new IllegalArgumentException("Generation target " + targetId + " does not exist"));
        ModelVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Model version " + versionId + " does not exist"));
        GenerationProfile profile = profileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Generation profile " + profileId + " does not exist"));
        TemplateVersion template = templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template version " + templateId + " does not exist"));

        validateRunIdentities(target, version, profile, template);
        InputSnapshot snapshot = snapshotFactory.create(version);
        Map<String, ProvisionalGenerationProfile.TypeMapping> primitiveTypes =
                VersionedGenerationProfileConfiguration.primitiveTypes(profile.getConfiguration());
        DomainModelIR ir = irMapper.map(snapshot, primitiveTypes);
        List<GenerationDiagnostic> diagnostics = profileCompatibilityDiagnostics(profile, target, snapshot, ir);

        GenerationRun existing = existingRun(target, version, profile, template);
        if (existing != null) {
            return new GenerationRunStart(existing, diagnostics);
        }

        GenerationRun run = GenerationRun.create(version.getModel(), version, target,
                target.getConfigurationRevision(), profile, template, snapshot.snapshotHash());
        run = runRepository.saveAndFlush(run);

        if (diagnostics.stream().anyMatch(GenerationDiagnostic::blocking)) {
            run.setState(GenerationState.FAILED);
            run = runRepository.saveAndFlush(run);
            recordBlockingDiagnostics(run, diagnostics);
        }
        return new GenerationRunStart(run, diagnostics);
    }

    private void validateRunIdentities(GenerationTarget target, ModelVersion version, GenerationProfile profile,
            TemplateVersion template) {
        UUID modelId = version.getModel().getId();
        if (!target.getModel().getId().equals(modelId)) {
            throw new IllegalArgumentException("Generation target model_id does not match model version model_id");
        }
        if (target.getConfigurationRevision() <= 0) {
            throw new IllegalArgumentException("target configuration_revision must be greater than zero");
        }
        if (profile.getProject() == null || !profile.getProject().getId().equals(version.getModel().getProject().getId())) {
            throw new IllegalArgumentException("Generation profile project_id does not match the model project_id");
        }
        if (!V1_PROFILE_KEY.equals(profile.getProfileKey())) {
            throw new IllegalArgumentException("profile_key option must be " + V1_PROFILE_KEY);
        }
        if (!template.getProfile().getId().equals(profile.getId())) {
            throw new IllegalArgumentException("template_version profile_id must identify the exact selected profile revision");
        }
        if (profile.getRevision() <= 0 || template.getRevision() <= 0) {
            throw new IllegalArgumentException("profile and template revisions must be greater than zero");
        }
        if (profile.getConfigurationHash() == null
                || !profile.getConfigurationHash().matches("[0-9a-f]{64}")
                || !VersionedGenerationProfileConfiguration.hash(profile.getConfiguration(), objectMapper)
                        .equals(profile.getConfigurationHash())) {
            throw new IllegalArgumentException("profile configuration_hash does not match its canonical configuration");
        }
        if (template.getContentHash() == null || !template.getContentHash().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("template content_hash must be 64 lowercase hexadecimal characters");
        }

        JsonNode configuration = profile.getConfiguration();
        String subsetVersion = configuration.path("supportedUmlSubset").path("version").asText();
        if (!VersionedGenerationProfileConfiguration.SUBSET_VERSION.equals(subsetVersion)) {
            throw new IllegalArgumentException("Profile option 'supportedUmlSubset.version' is incompatible: "
                    + subsetVersion);
        }
        if (!configuration.path("supportedUmlSubset").path("unsupportedFeaturesAreBlocking").asBoolean(false)) {
            throw new IllegalArgumentException("Profile option 'supportedUmlSubset.unsupportedFeaturesAreBlocking' "
                    + "must remain enabled");
        }
        String configuredEngine = configuration.path("databaseEngine").asText();
        if (!configuredEngine.equals(target.getDatabaseEngine())) {
            throw new IllegalArgumentException("Profile option 'databaseEngine' is incompatible with target "
                    + "database_engine " + target.getDatabaseEngine());
        }
    }

    private List<GenerationDiagnostic> profileCompatibilityDiagnostics(GenerationProfile profile,
            GenerationTarget target, InputSnapshot snapshot, DomainModelIR ir) {
        List<GenerationDiagnostic> diagnostics = new ArrayList<>(profileSubsetDiagnostics(
                profile.getConfiguration(), snapshot, ir));
        if (!"POSTGRESQL".equals(target.getDatabaseEngine())) {
            addProfileDiagnostic(diagnostics, null, "databaseEngine",
                    "Target option 'database_engine' is not supported by profile option 'databaseEngine'.");
        }
        return diagnostics.stream().distinct().toList();
    }

    private List<GenerationDiagnostic> profileSubsetDiagnostics(JsonNode configuration, InputSnapshot snapshot,
            DomainModelIR ir) {
        List<GenerationDiagnostic> diagnostics = new ArrayList<>(ir.diagnostics());
        JsonNode subset = configuration.path("supportedUmlSubset");

        for (ClassifierElement classifier : snapshot.classifiers()) {
            requireSupportedValue(subset.path("classifierKinds"), classifier.kind().name(),
                    "supportedUmlSubset.classifierKinds", classifier.id(), diagnostics);
        }
        for (AttributeElement attribute : snapshot.attributes()) {
            requireSupportedValue(subset.path("attributeKinds"), attribute.kind(),
                    "supportedUmlSubset.attributeKinds", attribute.id(), diagnostics);
            requireSupportedValue(subset.path("visibility"), attribute.visibility(),
                    "supportedUmlSubset.visibility", attribute.id(), diagnostics);
            if (attribute.defaultValue() != null
                    && !subset.path("supportsDefaultValues").asBoolean(false)) {
                addProfileDiagnostic(diagnostics, attribute.id(), "defaultValue",
                        "Snapshot feature 'defaultValue' is incompatible with profile option "
                                + "'supportedUmlSubset.supportsDefaultValues'.");
            }
        }
        for (OperationElement operation : snapshot.operations()) {
            if (!subset.path("supportsOperations").asBoolean(false)) {
                addProfileDiagnostic(diagnostics, operation.id(), "operations",
                        "Snapshot feature 'operations' is incompatible with profile option "
                                + "'supportedUmlSubset.supportsOperations'.");
            }
            requireSupportedValue(subset.path("visibility"), operation.visibility(),
                    "supportedUmlSubset.visibility", operation.id(), diagnostics);
        }
        for (ParameterElement parameter : snapshot.parameters()) {
            if (!subset.path("supportsParameters").asBoolean(false)) {
                addProfileDiagnostic(diagnostics, parameter.id(), "parameters",
                        "Snapshot feature 'parameters' is incompatible with profile option "
                                + "'supportedUmlSubset.supportsParameters'.");
            }
        }
        for (RelationshipElement relationship : snapshot.relationships()) {
            requireSupportedValue(subset.path("relationshipKinds"),
                    relationship.kind() == null ? null : relationship.kind().name(),
                    "supportedUmlSubset.relationshipKinds", relationship.id(), diagnostics);
        }
        return diagnostics;
    }

    private void requireSupportedValue(JsonNode supportedValues, String value, String option, UUID elementId,
            List<GenerationDiagnostic> diagnostics) {
        boolean supported = false;
        if (value != null && supportedValues.isArray()) {
            for (JsonNode supportedValue : supportedValues) {
                if (value.equals(supportedValue.asText())) {
                    supported = true;
                    break;
                }
            }
        }
        if (!supported) {
            addProfileDiagnostic(diagnostics, elementId, value == null ? option : value,
                    "Snapshot feature '" + value + "' is incompatible with profile option '" + option + "'.");
        }
    }

    private void addProfileDiagnostic(List<GenerationDiagnostic> diagnostics, UUID elementId, String feature,
            String message) {
        boolean alreadyReported = diagnostics.stream().anyMatch(diagnostic -> diagnostic.blocking()
                && Objects.equals(diagnostic.elementId(), elementId)
                && diagnostic.feature().equals(feature));
        if (!alreadyReported) {
            diagnostics.add(new GenerationDiagnostic("PROFILE_INCOMPATIBLE",
                    com.diagramador.domain.SeverityKind.ERROR, elementId, feature, message, true));
        }
    }

    private void recordBlockingDiagnostics(GenerationRun run, List<GenerationDiagnostic> diagnostics) {
        List<GenerationDiagnostic> blocking = diagnostics.stream().filter(GenerationDiagnostic::blocking).toList();
        String evidence = VersionedGenerationProfileConfiguration.canonicalJson(
                objectMapper.valueToTree(blocking), objectMapper);
        OffsetDateTime finishedAt = OffsetDateTime.now();
        VerificationResult result = VerificationResult.create(run, DIAGNOSTIC_CHECK_NAME, VerificationState.FAILED,
                evidence, InputSnapshotFactory.sha256(evidence), "DomainModelIRMapper", finishedAt, finishedAt);
        verificationRepository.saveAndFlush(result);
    }

    private GenerationRun existingRun(GenerationTarget target, ModelVersion version, GenerationProfile profile,
            TemplateVersion template) {
        return runRepository.findByTargetIdAndTargetConfigRevisionAndVersionIdAndProfileIdAndTemplateId(
                target.getId(), target.getConfigurationRevision(), version.getId(), profile.getId(), template.getId())
                .orElse(null);
    }

    private List<GenerationDiagnostic> diagnosticsFor(GenerationRun run) {
        return verificationRepository.findByRunIdAndCheckName(run.getId(), DIAGNOSTIC_CHECK_NAME)
                .map(result -> {
                    try {
                        JsonNode evidence = objectMapper.readTree(result.getEvidence());
                        List<GenerationDiagnostic> diagnostics = new ArrayList<>();
                        if (evidence.isArray()) {
                            evidence.forEach(item -> diagnostics.add(objectMapper.convertValue(item,
                                    GenerationDiagnostic.class)));
                        }
                        return List.copyOf(diagnostics);
                    } catch (Exception exception) {
                        throw new IllegalStateException("Stored generation diagnostics cannot be read", exception);
                    }
                }).orElseGet(List::of);
    }

    private GenerationProfile latestProfile(UUID projectId) {
        return profileRepository.findFirstByProjectIdAndProfileKeyOrderByRevisionDesc(projectId, V1_PROFILE_KEY)
                .orElse(null);
    }

    private TemplateVersion latestTemplate(UUID profileId) {
        return templateRepository.findFirstByProfileIdOrderByRevisionDesc(profileId).orElse(null);
    }

    private TargetConfiguration validateAndNormalize(TargetConfiguration configuration) {
        Objects.requireNonNull(configuration, "target configuration");
        String name = requireText(configuration.name(), "target name");
        String engine = configuration.databaseEngine() == null || configuration.databaseEngine().isBlank()
                ? "POSTGRESQL"
                : configuration.databaseEngine().trim().toUpperCase(java.util.Locale.ROOT);
        if (!"POSTGRESQL".equals(engine)) {
            throw new IllegalArgumentException("database_engine option supports POSTGRESQL only");
        }
        String databaseName = requireText(configuration.databaseName(), "database_name");
        String databaseSchema = configuration.databaseSchema() == null || configuration.databaseSchema().isBlank()
                ? "public"
                : configuration.databaseSchema().trim();
        String connectionConfigRef = configuration.connectionConfigRef() == null
                || configuration.connectionConfigRef().isBlank()
                        ? null
                        : configuration.connectionConfigRef().trim();
        ConnectionConfigRefValidator.validate(connectionConfigRef);
        rejectCredentialLikeMetadata("name", name);
        rejectCredentialLikeMetadata("database_engine", engine);
        rejectCredentialLikeMetadata("database_name", databaseName);
        rejectCredentialLikeMetadata("database_schema", databaseSchema);
        return new TargetConfiguration(name, engine, databaseName, databaseSchema, connectionConfigRef);
    }

    private void validateNonSecretConfiguration(JsonNode value, String path) {
        if (value.isObject()) {
            value.fields().forEachRemaining(entry -> {
                String childPath = path + "." + entry.getKey();
                if (ConnectionConfigRefValidator.hasSecretFieldName(entry.getKey())) {
                    throw new IllegalArgumentException("Profile configuration option '" + childPath
                            + "' must not contain secret material");
                }
                validateNonSecretConfiguration(entry.getValue(), childPath);
            });
        } else if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                validateNonSecretConfiguration(value.get(index), path + "[" + index + "]");
            }
        } else if (value.isTextual() && ConnectionConfigRefValidator.looksCredentialLike(value.asText())) {
            throw new IllegalArgumentException("Profile configuration option '" + path
                    + "' must not contain credential-shaped values");
        }
    }

    private void rejectCredentialLikeMetadata(String field, String value) {
        if (ConnectionConfigRefValidator.looksCredentialLike(value)) {
            throw new IllegalArgumentException("Target metadata field '" + field
                    + "' must not contain credential-shaped values");
        }
    }

    private void setTargetConfiguration(GenerationTarget target, TargetConfiguration configuration) {
        target.setName(configuration.name());
        target.setDatabaseEngine(configuration.databaseEngine());
        target.setDatabaseName(configuration.databaseName());
        target.setDatabaseSchema(configuration.databaseSchema());
        target.setConnectionConfigRef(configuration.connectionConfigRef());
    }

    private boolean sameConfiguration(GenerationTarget target, TargetConfiguration configuration) {
        return target.getName().equals(configuration.name())
                && target.getDatabaseEngine().equals(configuration.databaseEngine())
                && target.getDatabaseName().equals(configuration.databaseName())
                && target.getDatabaseSchema().equals(configuration.databaseSchema())
                && Objects.equals(target.getConnectionConfigRef(), configuration.connectionConfigRef());
    }

    private IllegalArgumentException duplicateTargetKey(UUID modelId, String targetKey) {
        return new IllegalArgumentException("target_key '" + targetKey + "' already exists for model " + modelId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > 512) {
            throw new IllegalArgumentException(field + " exceeds its maximum supported length");
        }
        return normalized;
    }

    public record TargetConfiguration(String name, String databaseEngine, String databaseName,
            String databaseSchema, String connectionConfigRef) {
    }

    public record GenerationReadiness(UUID versionId, String profile, boolean ready,
            List<GenerationDiagnostic> diagnostics) {

        public GenerationReadiness {
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(profile, "profile");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record GenerationRunStart(GenerationRun run, List<GenerationDiagnostic> diagnostics) {

        public GenerationRunStart {
            Objects.requireNonNull(run, "run");
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean isReadyForBackend() {
            return run.getState() == GenerationState.SUCCEEDED
                    && diagnostics.stream().noneMatch(GenerationDiagnostic::blocking);
        }
    }
}
