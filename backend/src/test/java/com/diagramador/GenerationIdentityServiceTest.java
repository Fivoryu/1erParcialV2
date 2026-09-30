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
import com.diagramador.generation.GenerationDiagnostic;
import com.diagramador.generation.GenerationIdentityService;
import com.diagramador.generation.GenerationIdentityService.GenerationRunStart;
import com.diagramador.generation.GenerationIdentityService.TargetConfiguration;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class GenerationIdentityServiceTest extends AbstractPostgresIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final GenerationIdentityService identityService;
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
    private final VerificationResultRepository verificationRepository;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    GenerationIdentityServiceTest(GenerationIdentityService identityService, CommandService commandService,
            ObjectMapper objectMapper, UserAccountRepository userAccountRepository, ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository, UmlModelRepository modelRepository,
            ModelVersionRepository versionRepository, GenerationProfileRepository profileRepository,
            TemplateVersionRepository templateRepository, GenerationTargetRepository targetRepository,
            GenerationRunRepository runRepository, VerificationResultRepository verificationRepository,
            TransactionTemplate transactionTemplate) {
        this.identityService = identityService;
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
