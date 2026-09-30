package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.diagramador.command.Author;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.command.Target;
import com.diagramador.domain.GenerationProfile;
import com.diagramador.domain.GenerationRun;
import com.diagramador.domain.GenerationState;
import com.diagramador.domain.GeneratedArtifact;
import com.diagramador.domain.GenerationTarget;
import com.diagramador.domain.ModelVersion;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.TemplateVersion;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.domain.VerificationResult;
import com.diagramador.domain.VerificationState;
import com.diagramador.generation.GenerationArtifactBundleBuilder.GeneratedArtifactBundle;
import com.diagramador.generation.GenerationArtifactService;
import com.diagramador.generation.GenerationDiagnostic;
import com.diagramador.generation.GenerationIdentityService;
import com.diagramador.generation.GenerationVerificationService;
import com.diagramador.generation.GenerationVerificationService.CheckSummary;
import com.diagramador.generation.GenerationVerificationService.VerificationSummary;
import com.diagramador.generation.InputSnapshot;
import com.diagramador.generation.InputSnapshotFactory;
import com.diagramador.generation.GenerationIdentityService.GenerationRunStart;
import com.diagramador.generation.GenerationIdentityService.TargetConfiguration;
import com.diagramador.persistence.GeneratedArtifactRepository;
import com.diagramador.persistence.GenerationProfileRepository;
import com.diagramador.persistence.GenerationRunRepository;
import com.diagramador.persistence.GenerationTargetRepository;
import com.diagramador.persistence.ModelVersionRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.TemplateVersionRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UserAccountRepository;
import com.diagramador.persistence.VerificationResultRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class GenerationIdentityServiceTest extends AbstractPostgresIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final GenerationIdentityService identityService;
    private final GenerationArtifactService artifactService;
    private final GenerationVerificationService verificationService;
    private final InputSnapshotFactory snapshotFactory;
    private final CommandService commandService;
    private final ObjectMapper objectMapper;
    private final UserAccountRepository userAccountRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UmlModelRepository modelRepository;
    private final ModelVersionRepository versionRepository;
    private final GenerationProfileRepository profileRepository;
    private final TemplateVersionRepository templateRepository;
    private final GenerationTargetRepository targetRepository;
    private final GenerationRunRepository runRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final VerificationResultRepository verificationRepository;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    GenerationIdentityServiceTest(GenerationIdentityService identityService,
            GenerationArtifactService artifactService, GenerationVerificationService verificationService,
            InputSnapshotFactory snapshotFactory,
            CommandService commandService, ObjectMapper objectMapper,
            UserAccountRepository userAccountRepository, ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository, UmlModelRepository modelRepository,
            ModelVersionRepository versionRepository, GenerationProfileRepository profileRepository,
            TemplateVersionRepository templateRepository, GenerationTargetRepository targetRepository,
            GenerationRunRepository runRepository, GeneratedArtifactRepository artifactRepository,
            VerificationResultRepository verificationRepository, TransactionTemplate transactionTemplate) {
        this.identityService = identityService;
        this.artifactService = artifactService;
        this.verificationService = verificationService;
        this.snapshotFactory = snapshotFactory;
        this.commandService = commandService;
        this.objectMapper = objectMapper;
        this.userAccountRepository = userAccountRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.modelRepository = modelRepository;
        this.versionRepository = versionRepository;
        this.profileRepository = profileRepository;
        this.templateRepository = templateRepository;
        this.targetRepository = targetRepository;
        this.runRepository = runRepository;
        this.artifactRepository = artifactRepository;
        this.verificationRepository = verificationRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Test
    void profileConfigurationIsCanonicalAndChangedOptionsCreateAnImmutableRevision() {
        Fixture fixture = fixture("profile-revisions");
        GenerationProfile original = identityService.provisionV1Profile(fixture.projectId());
        JsonNode originalConfiguration = original.getConfiguration().deepCopy();
        String originalHash = original.getConfigurationHash();

        GenerationProfile reordered = identityService.provisionProfile(fixture.projectId(),
                reverseObjectFieldOrder(originalConfiguration));
        assertThat(reordered.getId()).isEqualTo(original.getId());
        assertThat(reordered.getRevision()).isEqualTo(original.getRevision());
        assertThat(reordered.getConfigurationHash()).isEqualTo(originalHash);

        ObjectNode changedConfiguration = (ObjectNode) originalConfiguration.deepCopy();
        ((ObjectNode) changedConfiguration.path("generationOptions")).put("packagePrefix", "org.example.billing");
        GenerationProfile changed = identityService.provisionProfile(fixture.projectId(), changedConfiguration);

        assertThat(changed.getId()).isNotEqualTo(original.getId());
        assertThat(changed.getRevision()).isEqualTo(original.getRevision() + 1);
        assertThat(changed.getConfigurationHash()).isNotEqualTo(originalHash);
        assertThat(changed.getConfiguration().path("generationOptions").path("packagePrefix").asText())
                .isEqualTo("org.example.billing");

        GenerationProfile previousRevision = profileRepository.findByProjectIdAndProfileKeyAndRevision(
                fixture.projectId(), GenerationIdentityService.V1_PROFILE_KEY, original.getRevision()).orElseThrow();
        assertThat(previousRevision.getConfiguration()).isEqualTo(originalConfiguration);
        assertThat(previousRevision.getConfigurationHash()).isEqualTo(originalHash);
        assertThat(previousRevision.getRevision()).isEqualTo(1);
        System.out.printf("T4_PROFILE previousRevision=%d previousHash=%s changedRevision=%d changedHash=%s%n",
                previousRevision.getRevision(), previousRevision.getConfigurationHash(), changed.getRevision(),
                changed.getConfigurationHash());
    }

    @Test
    void v1ProfileRetainsAllPrimitiveMappingsInVersionedConfiguration() {
        Fixture fixture = fixture("profile-type-mappings");
        JsonNode mappings = identityService.provisionV1Profile(fixture.projectId())
                .getConfiguration().path("primitiveTypeMappings");
        Map<String, List<String>> expected = Map.ofEntries(
                Map.entry("String", List.of("String", "VARCHAR(255)")),
                Map.entry("Integer", List.of("Integer", "INTEGER")),
                Map.entry("Long", List.of("Long", "BIGINT")),
                Map.entry("Boolean", List.of("Boolean", "BOOLEAN")),
                Map.entry("Double", List.of("Double", "DOUBLE PRECISION")),
                Map.entry("BigDecimal", List.of("java.math.BigDecimal", "NUMERIC(19, 2)")),
                Map.entry("LocalDate", List.of("java.time.LocalDate", "DATE")),
                Map.entry("LocalDateTime", List.of("java.time.LocalDateTime", "TIMESTAMP")),
                Map.entry("UUID", List.of("java.util.UUID", "UUID")));

        assertThat(mappings.size()).isEqualTo(expected.size());
        expected.forEach((name, mapping) -> {
            assertThat(mappings.path(name).path("javaTypeName").asText()).isEqualTo(mapping.get(0));
            assertThat(mappings.path(name).path("sqlTypeName").asText()).isEqualTo(mapping.get(1));
        });
    }

    @Test
    void templateContentChangesCreateANewHashedRevision() {
        Fixture fixture = fixture("template-revisions");
        GenerationProfile profile = identityService.provisionV1Profile(fixture.projectId());
        String firstContent = "template: v1\n";
        TemplateVersion first = identityService.provisionTemplate(profile.getId(), "V1", firstContent);
        TemplateVersion sameContent = identityService.provisionTemplate(profile.getId(), "renamed label", firstContent);
        TemplateVersion changed = identityService.provisionTemplate(profile.getId(), "V2", "template: v2\n");
        TemplateVersion previousRevision = templateRepository.findByProfileIdAndRevision(profile.getId(), 1)
                .orElseThrow();

        assertThat(first.getContentHash()).isEqualTo(sha256(firstContent));
        assertThat(sameContent.getId()).isEqualTo(first.getId());
        assertThat(sameContent.getRevision()).isEqualTo(first.getRevision());
        assertThat(changed.getId()).isNotEqualTo(first.getId());
        assertThat(changed.getRevision()).isEqualTo(2);
        assertThat(changed.getContentHash()).isEqualTo(sha256("template: v2\n"));
        assertThat(previousRevision.getContentHash()).isEqualTo(sha256(firstContent));
    }

    @Test
    void targetKeysAreUniquePerModel() {
        Fixture firstModel = fixture("target-key-first-model");
        Fixture secondModel = fixture("target-key-second-model");
        TargetConfiguration configuration = targetConfiguration("primary", "database_primary",
                "vault://diagrammer/targets/primary");
        GenerationTarget firstTarget = identityService.createTarget(firstModel.model().getId(), "primary", configuration);

        assertThatThrownBy(() -> identityService.createTarget(firstModel.model().getId(), "primary", configuration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target_key 'primary' already exists for model");

        GenerationTarget sameKeyForAnotherModel = identityService.createTarget(secondModel.model().getId(), "primary",
                configuration);
        assertThat(sameKeyForAnotherModel.getId()).isNotEqualTo(firstTarget.getId());
        assertThat(identityService.listTargets(firstModel.model().getId()))
                .extracting(GenerationTarget::getTargetKey).containsExactly("primary");
        assertThat(identityService.listTargets(secondModel.model().getId()))
                .extracting(GenerationTarget::getTargetKey).containsExactly("primary");
    }

    @Test
    void credentialShapedTargetReferencesAreRejected() {
        Fixture fixture = fixture("target-credential-rejection");
        List<String> credentialShapes = List.of(
                "postgresql://alice:secret@db.example.test/orders",
                "jdbc:postgresql://db.example.test/orders?password=secret",
                "{\"client_secret\":\"secret-value\"}",
                "Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature",
                "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----");

        for (int index = 0; index < credentialShapes.size(); index++) {
            String reference = credentialShapes.get(index);
            assertThatThrownBy(() -> identityService.createTarget(fixture.model().getId(), "rejected-" + reference.hashCode(),
                    targetConfiguration("target", "database", reference)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("connection_config_ref");
        }

        GenerationTarget opaqueReference = identityService.createTarget(fixture.model().getId(), "safe-target",
                targetConfiguration("safe target", "safe_database", "vault://diagrammer/targets/safe"));
        assertThat(opaqueReference.getConnectionConfigRef()).isEqualTo("vault://diagrammer/targets/safe");
    }

    @Test
    void profileConfigurationReferenceIsRejectedBeforeItsHashCanBeProvisioned() {
        Fixture fixture = fixture("profile-secret-reference");
        ObjectNode configuration = objectMapper.createObjectNode();
        configuration.put("connectionConfigRef", "vault://diagrammer/t7/profile-secret");

        assertThatThrownBy(() -> identityService.provisionProfile(fixture.projectId(), configuration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configuration.connectionConfigRef");
        assertThat(profileRepository.findFirstByProjectIdAndProfileKeyOrderByRevisionDesc(fixture.projectId(),
                GenerationIdentityService.V1_PROFILE_KEY)).isEmpty();
    }

    @Test
    void sameRunIdentityReturnsExistingRun() {
        Fixture fixture = fixtureWithClassifier("idempotent-run", "CLASS");
        ModelVersion version = currentVersion(fixture);
        GenerationProfile profile = identityService.provisionV1Profile(fixture.projectId());
        TemplateVersion template = identityService.provisionV1Template(profile.getId());
        GenerationTarget target = identityService.createTarget(fixture.model().getId(), "main",
                targetConfiguration("Main", "main_database", "vault://diagrammer/targets/main"));

        GenerationRunStart first = identityService.startRun(target.getId(), version.getId(), profile.getId(),
                template.getId());
        GenerationRunStart repeated = identityService.startRun(target.getId(), version.getId(), profile.getId(),
                template.getId());

        assertThat(repeated.run().getId()).isEqualTo(first.run().getId());
        assertThat(runRepository.countByTargetId(target.getId())).isEqualTo(1);
        assertThat(first.run().getState()).isEqualTo(GenerationState.PENDING);
        assertThat(first.isReadyForBackend()).isFalse();
    }

    @Test
    void twoTargetsForOneModelCreateIndependentRunsAtTheirOwnConfigurationRevisions() {
        Fixture fixture = fixtureWithClassifier("two-targets", "CLASS");
        ModelVersion version = currentVersion(fixture);
        GenerationProfile profile = identityService.provisionV1Profile(fixture.projectId());
        TemplateVersion template = identityService.provisionV1Template(profile.getId());
        GenerationTarget primary = identityService.createTarget(fixture.model().getId(), "primary",
                targetConfiguration("Primary", "primary_database", "vault://diagrammer/targets/primary"));
        GenerationTarget reporting = identityService.createTarget(fixture.model().getId(), "reporting",
                targetConfiguration("Reporting", "reporting_database", "vault://diagrammer/targets/reporting"));
        reporting = identityService.updateTargetConfiguration(reporting.getId(),
                targetConfiguration("Reporting v2", "reporting_database_v2",
                        "vault://diagrammer/targets/reporting-v2"));

        assertThat(primary.getId()).isNotEqualTo(reporting.getId());
        assertThat(primary.getTargetKey()).isNotEqualTo(reporting.getTargetKey());
        assertThat(primary.getConfigurationRevision()).isEqualTo(1);
        assertThat(reporting.getConfigurationRevision()).isEqualTo(2);
        assertThat(targetRepository.findByModelIdOrderByTargetKeyAsc(fixture.model().getId()))
                .extracting(GenerationTarget::getTargetKey).containsExactly("primary", "reporting");

        GenerationRunStart primaryStart = identityService.startRun(primary.getId(), version.getId(), profile.getId(),
                template.getId());
        GenerationRunStart reportingStart = identityService.startRun(reporting.getId(), version.getId(), profile.getId(),
                template.getId());
        GenerationRunStart primaryRepeat = identityService.startRun(primary.getId(), version.getId(), profile.getId(),
                template.getId());

        assertThat(primaryStart.run().getId()).isNotEqualTo(reportingStart.run().getId());
        assertThat(primaryRepeat.run().getId()).isEqualTo(primaryStart.run().getId());
        assertThat(primaryStart.run().getTarget().getId()).isEqualTo(primary.getId());
        assertThat(reportingStart.run().getTarget().getId()).isEqualTo(reporting.getId());
        assertThat(primaryStart.run().getTargetConfigRevision()).isEqualTo(1);
        assertThat(reportingStart.run().getTargetConfigRevision()).isEqualTo(2);
        assertThat(runRepository.countByTargetId(primary.getId())).isEqualTo(1);
        assertThat(runRepository.countByTargetId(reporting.getId())).isEqualTo(1);
        System.out.printf("T4_D14 primaryRunId=%s primaryTargetConfigRevision=%d reportingRunId=%s "
                        + "reportingTargetConfigRevision=%d%n",
                primaryStart.run().getId(), primaryStart.run().getTargetConfigRevision(),
                reportingStart.run().getId(), reportingStart.run().getTargetConfigRevision());
    }

    @Test
    void artifactGenerationIsDeterministicTargetScopedSecretSafeAndPersisted() throws Exception {
        Fixture fixture = fixtureWithClassifier("t7-artifact-persistence", "CLASS");
        ModelVersion version = currentVersion(fixture);
        GenerationProfile profile = identityService.provisionV1Profile(fixture.projectId());
        TemplateVersion template = identityService.provisionV1Template(profile.getId());
        GenerationTarget primary = identityService.createTarget(fixture.model().getId(), "primary",
                targetConfiguration("Primary", "primary_database", "vault://diagrammer/targets/primary"));
        GenerationTarget reporting = identityService.createTarget(fixture.model().getId(), "reporting",
                targetConfiguration("Reporting", "reporting_database", "vault://diagrammer/targets/reporting"));
        reporting = identityService.updateTargetConfiguration(reporting.getId(),
                targetConfiguration("Reporting v2", "reporting_database_v2",
                        "vault://diagrammer/targets/reporting-v2"));

        GenerationRun primaryRun = identityService.startRun(primary.getId(), version.getId(), profile.getId(),
                template.getId()).run();
        GenerationRun reportingRun = identityService.startRun(reporting.getId(), version.getId(), profile.getId(),
                template.getId()).run();
        assertThat(primaryRun.getModel().getId()).isEqualTo(reportingRun.getModel().getId());
        assertThat(primaryRun.getVersion().getId()).isEqualTo(reportingRun.getVersion().getId());
        assertThat(primaryRun.getProfile().getId()).isEqualTo(reportingRun.getProfile().getId());
        assertThat(primaryRun.getTemplate().getId()).isEqualTo(reportingRun.getTemplate().getId());
        assertThat(primary.getId()).isNotEqualTo(reporting.getId());
        assertThat(primary.getTargetKey()).isNotEqualTo(reporting.getTargetKey());
        assertThat(primaryRun.getId()).isNotEqualTo(reportingRun.getId());
        assertThat(primaryRun.getTargetConfigRevision()).isEqualTo(1);
        assertThat(reportingRun.getTargetConfigRevision()).isEqualTo(2);

        InputSnapshot snapshot = snapshotFactory.create(version);
        String firstSecretReference = "vault://diagrammer/t7/target-config/secret-a";
        String secondSecretReference = "vault://diagrammer/t7/target-config/secret-b";
        primary.setConnectionConfigRef(firstSecretReference);
        targetRepository.saveAndFlush(primary);
        GeneratedArtifactBundle primaryFirst = artifactService.generate(primaryRun.getId(), snapshot);
        primary.setConnectionConfigRef(secondSecretReference);
        targetRepository.saveAndFlush(primary);
        GeneratedArtifactBundle primaryRepeat = artifactService.generate(primaryRun.getId(), snapshot);

        assertThat(firstSecretReference).isNotEqualTo(secondSecretReference);
        assertThat(primaryFirst.artifactSetHash()).isEqualTo(primaryRepeat.artifactSetHash());
        assertThat(primaryFirst.artifactZipHash()).isEqualTo(primaryRepeat.artifactZipHash());
        assertThat(primaryFirst.zipBytes()).containsExactly(primaryRepeat.zipBytes());
        assertThat(primaryFirst.files().values()).noneMatch(content -> content.contains(firstSecretReference)
                || content.contains(secondSecretReference));
        assertThat(primaryFirst.provenanceJson()).doesNotContain(firstSecretReference, secondSecretReference);

        String reportingSecretReference = "vault://diagrammer/t7/target-config/reporting-secret";
        reporting.setConnectionConfigRef(reportingSecretReference);
        targetRepository.saveAndFlush(reporting);
        GeneratedArtifactBundle reportingBundle = artifactService.generate(reportingRun.getId(), snapshot);
        JsonNode primaryProvenance = objectMapper.readTree(primaryFirst.provenanceJson());
        JsonNode reportingProvenance = objectMapper.readTree(reportingBundle.provenanceJson());
        assertThat(primaryProvenance.path("target").path("targetId").asText()).isEqualTo(primary.getId().toString());
        assertThat(primaryProvenance.path("target").path("targetKey").asText()).isEqualTo("primary");
        assertThat(primaryProvenance.path("target").path("configurationRevision").asInt()).isEqualTo(1);
        assertThat(reportingProvenance.path("target").path("targetId").asText()).isEqualTo(reporting.getId().toString());
        assertThat(reportingProvenance.path("target").path("targetKey").asText()).isEqualTo("reporting");
        assertThat(reportingProvenance.path("target").path("configurationRevision").asInt()).isEqualTo(2);
        assertThat(primaryProvenance.path("verificationStatus").asText()).isEqualTo("NOT_RUN");
        assertThat(reportingBundle.files().values()).noneMatch(content -> content.contains(reportingSecretReference));
        assertThat(primaryFirst.files().get("pom.xml")).isNotEqualTo(reportingBundle.files().get("pom.xml"));
        assertThat(primaryFirst.files().get("pom.xml")).contains("<artifactId>generated-primary</artifactId>");
        assertThat(reportingBundle.files().get("pom.xml")).contains("<artifactId>generated-reporting</artifactId>");
        assertThat(primaryFirst.files().get("src/main/java/com/generated/primary/GeneratedApplication.java"))
                .contains("package com.generated.primary;");
        assertThat(reportingBundle.files().get("src/main/java/com/generated/reporting/GeneratedApplication.java"))
                .contains("package com.generated.reporting;");
        assertThat(primaryFirst.artifactSetHash()).isNotEqualTo(reportingBundle.artifactSetHash());
        assertThat(primaryFirst.artifactZipHash()).isNotEqualTo(reportingBundle.artifactZipHash());
        assertThat(java.util.Arrays.equals(primaryFirst.zipBytes(), reportingBundle.zipBytes())).isFalse();

        for (var expected : primaryFirst.completeManifest()) {
            GeneratedArtifact persisted = artifactRepository.findByRunIdOrderByPathAsc(primaryRun.getId()).stream()
                    .filter(artifact -> artifact.getPath().equals(expected.path())).findFirst().orElseThrow();
            assertThat(persisted.getKind()).isEqualTo(expected.kind());
            assertThat(persisted.getContentHash()).isEqualTo(expected.contentHash());
            assertThat(persisted.getSizeBytes()).isEqualTo(expected.sizeBytes());
        }
        List<GeneratedArtifact> persistedRows = artifactRepository.findByRunIdOrderByPathAsc(primaryRun.getId());
        assertThat(persistedRows).hasSize(primaryFirst.completeManifest().size());
        assertThat(persistedRows).extracting(GeneratedArtifact::getPath)
                .containsExactlyElementsOf(primaryFirst.files().keySet().stream().toList());
        GenerationRun persistedPrimaryRun = runRepository.findById(primaryRun.getId()).orElseThrow();
        GenerationRun persistedReportingRun = runRepository.findById(reportingRun.getId()).orElseThrow();
        assertThat(persistedPrimaryRun.getState()).isEqualTo(GenerationState.SUCCEEDED);
        assertThat(persistedPrimaryRun.getArtifactSetHash()).isEqualTo(primaryFirst.artifactSetHash());
        assertThat(persistedPrimaryRun.getArtifactZipHash()).isEqualTo(primaryFirst.artifactZipHash());
        assertThat(persistedReportingRun.getState()).isEqualTo(GenerationState.SUCCEEDED);
        assertThat(persistedReportingRun.getArtifactSetHash()).isEqualTo(reportingBundle.artifactSetHash());
        assertThat(persistedReportingRun.getArtifactZipHash()).isEqualTo(reportingBundle.artifactZipHash());
        assertThat(runRepository.countByTargetId(primary.getId())).isEqualTo(1);
        assertThat(runRepository.countByTargetId(reporting.getId())).isEqualTo(1);
        System.out.printf("T7_D14 primarySet=%s primaryZip=%s primaryZipBytes=%d reportingSet=%s "
                        + "reportingZip=%s reportingZipBytes=%d persistedArtifacts=%d%n",
                primaryFirst.artifactSetHash(), primaryFirst.artifactZipHash(), primaryFirst.zipBytes().length,
                reportingBundle.artifactSetHash(), reportingBundle.artifactZipHash(), reportingBundle.zipBytes().length,
                persistedRows.size());
    }

    @Test
    void verificationRecordsTheCompletePerRunCheckSetAndKeepsRunsIsolated() {
        Fixture fixture = fixtureWithClassifier("t8-verification-runs", "CLASS");
        ModelVersion version = currentVersion(fixture);
        GenerationProfile profile = identityService.provisionV1Profile(fixture.projectId());
        TemplateVersion template = identityService.provisionV1Template(profile.getId());
        GenerationTarget primary = identityService.createTarget(fixture.model().getId(), "primary",
                targetConfiguration("Primary", "primary_database", "vault://diagrammer/t8/primary"));
        GenerationTarget reporting = identityService.createTarget(fixture.model().getId(), "reporting",
                targetConfiguration("Reporting", "reporting_database", "vault://diagrammer/t8/reporting"));
        GenerationRun primaryRun = identityService.startRun(primary.getId(), version.getId(), profile.getId(),
                template.getId()).run();
        GenerationRun reportingRun = identityService.startRun(reporting.getId(), version.getId(), profile.getId(),
                template.getId()).run();
        InputSnapshot snapshot = snapshotFactory.create(version);
        artifactService.generate(primaryRun.getId(), snapshot);
        artifactService.generate(reportingRun.getId(), snapshot);

        VerificationSummary primarySummary = verificationService.verify(primaryRun.getId(), snapshot);
        VerificationSummary reportingSummary = verificationService.verify(reportingRun.getId(), snapshot);
        Set<String> expectedExecutedCheckNames = Set.of(
                "artifact_set_structure", "flyway_migration_file", "artifact_hashes",
                "frozen_input_determinism", "target_safe_configuration");
        Set<String> expectedNotRunCheckNames = Set.of(
                "jpa_entities_and_relationships", "repositories", "services_and_supported_operations",
                "rest_dto_openapi_behavior", "validation_and_error_handling", "security", "authorization", "audit",
                "postgres_migration_execution", "generated_backend_compilation", "generated_backend_startup",
                "generated_backend_health", "generated_backend_contract_smoke", "generated_backend_tests",
                "docker_compose_execution", "generated_backend_runtime_reproducibility");
        Set<String> expectedCheckNames = new java.util.HashSet<>(expectedExecutedCheckNames);
        expectedCheckNames.addAll(expectedNotRunCheckNames);
        assertThat(GenerationVerificationService.EXECUTED_CHECK_NAMES)
                .containsExactlyInAnyOrderElementsOf(expectedExecutedCheckNames);
        assertThat(GenerationVerificationService.NOT_EXECUTED_CHECKS)
                .extracting(GenerationVerificationService.NotRunCheck::checkName)
                .containsExactlyInAnyOrderElementsOf(expectedNotRunCheckNames);
        Map<String, VerificationState> primaryStates = primarySummary.checks().stream()
                .collect(java.util.stream.Collectors.toMap(CheckSummary::checkName, CheckSummary::state));

        assertThat(primarySummary.generationState()).isEqualTo(GenerationState.SUCCEEDED);
        assertThat(primarySummary.status()).isEqualTo(VerificationState.NOT_RUN);
        assertThat(primarySummary.verifiedBackend()).isFalse();
        assertThat(primaryStates.keySet()).containsExactlyInAnyOrderElementsOf(expectedCheckNames);
        assertThat(expectedExecutedCheckNames).allSatisfy(checkName ->
                assertThat(primaryStates.get(checkName)).isEqualTo(VerificationState.PASSED));
        assertThat(GenerationVerificationService.NOT_EXECUTED_CHECKS).allSatisfy(check -> {
            assertThat(primaryStates.get(check.checkName())).isEqualTo(VerificationState.NOT_RUN);
            assertThat(check.reason()).isNotBlank();
            CheckSummary notRunResult = primarySummary.checks().stream()
                    .filter(result -> result.checkName().equals(check.checkName())).findFirst().orElseThrow();
            assertThat(notRunResult.evidence()).contains("NOT_RUN", check.reason(), primaryRun.getId().toString());
            assertThat(notRunResult.toolName()).isEqualTo("Cycle 3 per-run check not executed");
        });
        assertThat(reportingSummary.checks()).extracting(CheckSummary::checkName)
                .containsExactlyInAnyOrderElementsOf(expectedCheckNames);
        assertThat(reportingSummary.status()).isEqualTo(VerificationState.NOT_RUN);
        assertThat(reportingSummary.verifiedBackend()).isFalse();
        assertThat(primarySummary.checks()).allSatisfy(result -> {
            assertThat(result.evidence()).contains(primaryRun.getId().toString());
            assertThat(result.evidenceHash()).isNull();
        });
        assertThat(primarySummary.checks()).filteredOn(result -> result.state() == VerificationState.PASSED)
                .extracting(CheckSummary::toolName).containsExactlyInAnyOrder(
                        "generated artifact structure inspection",
                        "generated migration content inspection",
                        "frozen artifact hash recomputation",
                        "frozen input artifact rebuild comparison",
                        "credential-shaped value inspection");

        List<VerificationResult> primaryRows = verificationRepository
                .findByRunIdOrderByCreatedAtAsc(primaryRun.getId());
        List<VerificationResult> reportingRows = verificationRepository
                .findByRunIdOrderByCreatedAtAsc(reportingRun.getId());
        assertThat(primaryRows).hasSize(expectedCheckNames.size());
        assertThat(reportingRows).hasSize(expectedCheckNames.size());
        assertThat(primaryRows).allSatisfy(result ->
                assertThat(result.getRun().getId()).isEqualTo(primaryRun.getId()));
        assertThat(reportingRows).allSatisfy(result ->
                assertThat(result.getRun().getId()).isEqualTo(reportingRun.getId()));
        assertThat(verificationService.readSummary(primaryRun.getId()).checks())
                .extracting(CheckSummary::checkName).containsExactlyInAnyOrderElementsOf(expectedCheckNames);
        assertThat(runRepository.findById(primaryRun.getId()).orElseThrow().getState())
                .isEqualTo(GenerationState.SUCCEEDED);
        assertThat(runRepository.findById(reportingRun.getId()).orElseThrow().getState())
                .isEqualTo(GenerationState.SUCCEEDED);

        primarySummary.checks().stream().sorted(java.util.Comparator.comparing(CheckSummary::checkName))
                .forEach(result -> System.out.printf("T8_NORMAL run=%s check=%s state=%s tool=%s evidence=%s%n",
                        primaryRun.getId(), result.checkName(), result.state(), result.toolName(), result.evidence()));
    }

    @Test
    void incompletePersistedArtifactManifestFailsStructureAndNeverClaimsVerifiedBackend() {
        Fixture fixture = fixtureWithClassifier("t8-incomplete-artifact-set", "CLASS");
        ModelVersion version = currentVersion(fixture);
        GenerationProfile profile = identityService.provisionV1Profile(fixture.projectId());
        TemplateVersion template = identityService.provisionV1Template(profile.getId());
        GenerationTarget target = identityService.createTarget(fixture.model().getId(), "primary",
                targetConfiguration("Primary", "primary_database", "vault://diagrammer/t8/incomplete"));
        GenerationRun run = identityService.startRun(target.getId(), version.getId(), profile.getId(),
                template.getId()).run();
        InputSnapshot snapshot = snapshotFactory.create(version);
        artifactService.generate(run.getId(), snapshot);

        GeneratedArtifact missingRepository = artifactRepository.findByRunIdOrderByPathAsc(run.getId()).stream()
                .filter(artifact -> artifact.getKind().equals("repository")).findFirst().orElseThrow();
        artifactRepository.delete(missingRepository);
        artifactRepository.flush();

        VerificationSummary summary = verificationService.verify(run.getId(), snapshot);
        CheckSummary structure = summary.checks().stream()
                .filter(result -> result.checkName().equals(GenerationVerificationService.ARTIFACT_SET_STRUCTURE))
                .findFirst().orElseThrow();

        assertThat(structure.state()).isEqualTo(VerificationState.FAILED);
        assertThat(structure.state()).isNotEqualTo(VerificationState.PASSED);
        assertThat(structure.evidence()).contains("Structure evaluation executed", "persistence/repositories");
        assertThat(summary.status()).isEqualTo(VerificationState.FAILED);
        assertThat(summary.verifiedBackend()).isFalse();
        assertThat(runRepository.findById(run.getId()).orElseThrow().getState())
                .isEqualTo(GenerationState.SUCCEEDED);
        System.out.printf("T8_INCOMPLETE run=%s check=%s state=%s evidence=%s%n",
                run.getId(), structure.checkName(), structure.state(), structure.evidence());
    }

    @Test
    void credentialShapedTargetConfigurationNeverReachesVerificationEvidenceOrHashes() {
        Fixture fixture = fixtureWithClassifier("t8-secret-evidence", "CLASS");
        ModelVersion version = currentVersion(fixture);
        GenerationProfile profile = identityService.provisionV1Profile(fixture.projectId());
        TemplateVersion template = identityService.provisionV1Template(profile.getId());
        GenerationTarget target = identityService.createTarget(fixture.model().getId(), "primary",
                targetConfiguration("Primary", "primary_database", "vault://diagrammer/t8/safe"));
        GenerationRun run = identityService.startRun(target.getId(), version.getId(), profile.getId(),
                template.getId()).run();
        InputSnapshot snapshot = snapshotFactory.create(version);
        GeneratedArtifactBundle generated = artifactService.generate(run.getId(), snapshot);
        String credential = "jdbc:postgresql://db.example.test/orders?password=t8-hidden-credential";
        target.setConnectionConfigRef(credential);
        targetRepository.saveAndFlush(target);

        VerificationSummary summary = verificationService.verify(run.getId(), snapshot);
        CheckSummary targetSafety = summary.checks().stream()
                .filter(result -> result.checkName().equals(GenerationVerificationService.TARGET_SAFE_CONFIGURATION))
                .findFirst().orElseThrow();
        List<VerificationResult> rows = verificationRepository.findByRunIdOrderByCreatedAtAsc(run.getId());

        assertThat(targetSafety.state()).isEqualTo(VerificationState.FAILED);
        assertThat(targetSafety.evidence()).contains("credential-shaped data was detected")
                .doesNotContain(credential, "t8-hidden-credential");
        assertThat(generated.files().values()).noneMatch(content -> content.contains(credential));
        assertThat(generated.provenanceJson()).doesNotContain(credential);
        assertThat(rows).allSatisfy(result -> {
            assertThat(result.getEvidence()).doesNotContain(credential, "t8-hidden-credential");
            assertThat(result.getEvidenceHash()).isNull();
        });
    }

    @Test
    void incompatibleProfileOptionFailsRunBeforeBackendIsReady() {
        Fixture fixture = fixtureWithClassifier("incompatible-profile", "CLASS");
        ObjectNode configuration = (ObjectNode) identityService.provisionV1Profile(fixture.projectId())
                .getConfiguration().deepCopy();
        ArrayNode classifierKinds = (ArrayNode) configuration.path("supportedUmlSubset").path("classifierKinds");
        classifierKinds.removeAll().add("ABSTRACT_CLASS").add("PACKAGE");
        GenerationProfile incompatible = identityService.provisionProfile(fixture.projectId(), configuration);
        TemplateVersion template = identityService.provisionV1Template(incompatible.getId());
        GenerationTarget target = identityService.createTarget(fixture.model().getId(), "incompatible",
                targetConfiguration("Incompatible", "incompatible_database",
                        "vault://diagrammer/targets/incompatible"));

        GenerationRunStart start = identityService.startRun(target.getId(), currentVersion(fixture).getId(),
                incompatible.getId(), template.getId());

        assertThat(start.run().getState()).isEqualTo(GenerationState.FAILED);
        assertThat(start.isReadyForBackend()).isFalse();
        assertThat(start.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.blocking()).isTrue();
            assertThat(diagnostic.message()).contains("supportedUmlSubset.classifierKinds");
        });
        VerificationResult persisted = verificationRepository
                .findByRunIdAndCheckName(start.run().getId(), GenerationIdentityService.DIAGNOSTIC_CHECK_NAME)
                .orElseThrow();
        assertThat(persisted.getState()).isEqualTo(VerificationState.FAILED);
        assertThat(persisted.getEvidence()).contains("supportedUmlSubset.classifierKinds");
    }

    @Test
    void blockingMapperDiagnosticsFailRunBeforeGenerationIsReady() {
        Fixture fixture = fixtureWithClassifier("blocking-mapper-diagnostic", "INTERFACE");
        GenerationProfile profile = identityService.provisionV1Profile(fixture.projectId());
        TemplateVersion template = identityService.provisionV1Template(profile.getId());
        GenerationTarget target = identityService.createTarget(fixture.model().getId(), "unsupported",
                targetConfiguration("Unsupported", "unsupported_database",
                        "vault://diagrammer/targets/unsupported"));

        GenerationRunStart start = identityService.startRun(target.getId(), currentVersion(fixture).getId(),
                profile.getId(), template.getId());

        assertThat(start.run().getState()).isEqualTo(GenerationState.FAILED);
        assertThat(start.isReadyForBackend()).isFalse();
        assertThat(start.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("UNSUPPORTED_UML_FEATURE");
            assertThat(diagnostic.feature()).isEqualTo("INTERFACE");
            assertThat(diagnostic.blocking()).isTrue();
        });
        assertThat(verificationRepository.findByRunIdAndCheckName(start.run().getId(),
                GenerationIdentityService.DIAGNOSTIC_CHECK_NAME)).hasValueSatisfying(result -> {
                    assertThat(result.getState()).isEqualTo(VerificationState.FAILED);
                    assertThat(result.getEvidence()).contains("INTERFACE");
                });
    }

    private Fixture fixture(String label) {
        return transactionTemplate.execute(status -> {
            UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            UserAccount author = userAccountRepository.save(UserAccount.create("generation-" + UUID.randomUUID(),
                    "Generation Test", "generation-" + UUID.randomUUID() + "@example.test"));
            Project project = projectRepository.save(Project.create(owner, "Generation " + label,
                    "generation identity tests"));
            UmlModel model = modelRepository.save(UmlModel.create(project, "Generation Model"));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            projectMemberRepository.save(ProjectMember.create(project, author, ProjectMemberRole.EDITOR));
            return new Fixture(project.getId(), model, new Author(author.getId(), "generation-test"));
        });
    }

    private Fixture fixtureWithClassifier(String label, String kind) {
        Fixture fixture = fixture(label);
        UUID classifierId = UUID.randomUUID();
        CommandResult result = command(fixture, 0, "classifier.create",
                Map.of("id", classifierId, "name", "Account", "kind", kind), List.of());
        assertAccepted(result);
        return fixture;
    }

    private CommandResult command(Fixture fixture, long revision, String type, Object payload, List<Target> targets) {
        return commandService.execute(new OperationEnvelope(UUID.randomUUID(), type, fixture.author(),
                fixture.projectId(), fixture.model().getId(), revision, targets, List.of(), payload,
                CommandOrigin.MANUAL, null, UUID.randomUUID().toString()));
    }

    private ModelVersion currentVersion(Fixture fixture) {
        return versionRepository.findTopByModelIdOrderByRevisionDesc(fixture.model().getId()).orElseThrow();
    }

    private TargetConfiguration targetConfiguration(String name, String databaseName, String connectionConfigRef) {
        return new TargetConfiguration(name, "POSTGRESQL", databaseName, "public", connectionConfigRef);
    }

    private JsonNode reverseObjectFieldOrder(JsonNode value) {
        if (value.isObject()) {
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            value.fields().forEachRemaining(fields::add);
            ObjectNode reversed = objectMapper.createObjectNode();
            for (int index = fields.size() - 1; index >= 0; index--) {
                Map.Entry<String, JsonNode> field = fields.get(index);
                reversed.set(field.getKey(), reverseObjectFieldOrder(field.getValue()));
            }
            return reversed;
        }
        if (value.isArray()) {
            ArrayNode reversed = objectMapper.createArrayNode();
            value.forEach(item -> reversed.add(reverseObjectFieldOrder(item)));
            return reversed;
        }
        return value.deepCopy();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte digestByte : digest) {
                hex.append(String.format("%02x", digestByte));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void assertAccepted(CommandResult result) {
        assertThat(result.status()).withFailMessage("diagnostics: %s", result.diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);
    }

    private record Fixture(UUID projectId, UmlModel model, Author author) {
    }
}
