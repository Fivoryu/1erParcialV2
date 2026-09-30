package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.diagramador.domain.GenerationProfile;
import com.diagramador.domain.GenerationRun;
import com.diagramador.domain.GenerationState;
import com.diagramador.domain.ModelVersion;
import com.diagramador.domain.TemplateVersion;
import com.diagramador.generation.GenerationArtifactBundleBuilder.GeneratedArtifactBundle;
import com.diagramador.generation.GenerationArtifactService;
import com.diagramador.generation.GenerationIdentityService;
import com.diagramador.generation.GenerationIdentityService.GenerationRunStart;
import com.diagramador.generation.InputSnapshot;
import com.diagramador.generation.InputSnapshotFactory;
import com.diagramador.persistence.GenerationRunRepository;
import com.diagramador.persistence.GenerationTargetRepository;
import com.diagramador.persistence.ModelVersionRepository;
import com.diagramador.persistence.VerificationResultRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class GenerationApiTest extends AbstractPostgresIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SEED_OWNER_SUBJECT = "seed-owner-local";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final GenerationIdentityService identityService;
    private final GenerationArtifactService artifactService;
    private final InputSnapshotFactory snapshotFactory;
    private final ModelVersionRepository versionRepository;
    private final GenerationRunRepository runRepository;
    private final GenerationTargetRepository targetRepository;
    private final VerificationResultRepository verificationRepository;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    GenerationApiTest(MockMvc mockMvc, ObjectMapper objectMapper, GenerationIdentityService identityService,
            GenerationArtifactService artifactService, InputSnapshotFactory snapshotFactory,
            ModelVersionRepository versionRepository, GenerationRunRepository runRepository,
            GenerationTargetRepository targetRepository, VerificationResultRepository verificationRepository,
            JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.identityService = identityService;
        this.artifactService = artifactService;
        this.snapshotFactory = snapshotFactory;
        this.versionRepository = versionRepository;
        this.runRepository = runRepository;
        this.targetRepository = targetRepository;
        this.verificationRepository = verificationRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void generationTargetsCanBeCreatedAndListedForTheSameModel() throws Exception {
        Fixture fixture = fixture("target-list");
        JsonNode primary = createTarget(fixture, "primary", "Primary", "primary_database",
                "vault://diagrammer/targets/primary").path("result");
        JsonNode reporting = createTarget(fixture, "reporting", "Reporting", "reporting_database",
                "vault://diagrammer/targets/reporting").path("result");

        JsonNode targets = getJson("/projects/{projectId}/generation-targets", fixture.projectId());

        assertThat(primary.path("modelId").asText()).isEqualTo(fixture.modelId().toString());
        assertThat(reporting.path("modelId").asText()).isEqualTo(fixture.modelId().toString());
        assertThat(primary.path("targetId").asText()).isNotEqualTo(reporting.path("targetId").asText());
        assertThat(targets).hasSize(2);
        assertThat(targets.findValuesAsText("targetKey")).containsExactly("primary", "reporting");
    }

    @Test
    void generationCanBeStartedUsingOnlyDiscoveredModelAndProfileIdentities() throws Exception {
        JsonNode project = createProject("Generation API client flow " + UUID.randomUUID());
        UUID projectId = UUID.fromString(project.path("id").asText());
        JsonNode initialModel = getJson("/projects/{projectId}/model", projectId);
        UUID modelId = UUID.fromString(initialModel.path("id").asText());
        postCommand(projectId, modelId, UUID.randomUUID());

        MvcResult modelResponse = mockMvc.perform(get("/projects/{projectId}/model", projectId)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode model = objectMapper.readTree(modelResponse.getResponse().getContentAsByteArray());
        MvcResult profilesResponse = mockMvc.perform(get("/projects/{projectId}/generation-profiles", projectId)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode profiles = objectMapper.readTree(profilesResponse.getResponse().getContentAsByteArray());
        assertThat(profiles).hasSize(1);
        JsonNode profile = profiles.get(0);

        Map<String, Object> targetRequest = new HashMap<>();
        targetRequest.put("targetKey", "client");
        targetRequest.put("name", "Client");
        targetRequest.put("databaseEngine", "POSTGRESQL");
        targetRequest.put("databaseName", "client_database");
        targetRequest.put("databaseSchema", "public");
        targetRequest.put("connectionConfigRef", null);
        byte[] targetRequestBody = objectMapper.writeValueAsBytes(targetRequest);
        MvcResult targetResponse = mockMvc.perform(post("/projects/{projectId}/generation-targets", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(targetRequestBody))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode target = objectMapper.readTree(targetResponse.getResponse().getContentAsByteArray()).path("result");
        Map<String, Object> request = Map.of(
                "modelId", model.path("id").asText(),
                "versionId", model.path("versionId").asText(),
                "targetId", target.path("targetId").asText(),
                "profileId", profile.path("profileId").asText(),
                "templateId", profile.path("templateId").asText(),
                "idempotencyKey", "client-flow-" + UUID.randomUUID());
        byte[] generationRequestBody = objectMapper.writeValueAsBytes(request);
        MvcResult generationResponse = mockMvc.perform(post("/projects/{projectId}/generations", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(generationRequestBody))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(generationResponse.getResponse().getContentAsByteArray());
        JsonNode run = response.path("result");

        System.out.printf("T9_CLIENT_FLOW modelResponse=%s profilesResponse=%s targetRequest=%s "
                        + "targetResponse=%s generationRequest=%s generationResponse=%s%n",
                new String(modelResponse.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8),
                new String(profilesResponse.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8),
                new String(targetRequestBody, StandardCharsets.UTF_8),
                new String(targetResponse.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8),
                new String(generationRequestBody, StandardCharsets.UTF_8),
                new String(generationResponse.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));

        assertThat(response.path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(run.path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("modelId").asText()).isEqualTo(model.path("id").asText());
        assertThat(run.path("versionId").asText()).isEqualTo(model.path("versionId").asText());
        assertThat(run.path("modelRevision").asLong()).isEqualTo(model.path("revision").asLong());
        assertThat(run.path("profileId").asText()).isEqualTo(profile.path("profileId").asText());
        assertThat(run.path("profileRevision").asInt()).isEqualTo(profile.path("profileRevision").asInt());
        assertThat(run.path("templateId").asText()).isEqualTo(profile.path("templateId").asText());
        assertThat(run.path("templateRevision").asInt()).isEqualTo(profile.path("templateRevision").asInt());
    }

    @Test
    void generationProfileListingIsIdempotentAndReturnsExactRevisionIdentities() throws Exception {
        JsonNode project = createProject("Generation API profile listing " + UUID.randomUUID());
        UUID projectId = UUID.fromString(project.path("id").asText());

        JsonNode first = getJson("/projects/{projectId}/generation-profiles", projectId);
        JsonNode second = getJson("/projects/{projectId}/generation-profiles", projectId);

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).path("profileId").asText())
                .isEqualTo(first.get(0).path("profileId").asText());
        assertThat(second.get(0).path("profileRevision").asInt())
                .isEqualTo(first.get(0).path("profileRevision").asInt());
        assertThat(second.get(0).path("templateId").asText())
                .isEqualTo(first.get(0).path("templateId").asText());
        assertThat(second.get(0).path("templateRevision").asInt())
                .isEqualTo(first.get(0).path("templateRevision").asInt());
        Long profileCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM diagrammer.generation_profile WHERE project_id = ?", Long.class, projectId);
        assertThat(profileCount).isEqualTo(1L);
    }

    @Test
    void duplicateTargetKeyReturnsHttp200RejectionNamingTheKey() throws Exception {
        Fixture fixture = fixture("duplicate-target-key");
        createTarget(fixture, "primary", "Primary", "primary_database", null);

        JsonNode rejection = createTarget(fixture, "primary", "Duplicate", "duplicate_database", null);

        assertThat(rejection.path("outcome").asText()).isEqualTo("REJECTED");
        assertThat(rejection.path("code").asText()).isEqualTo("TARGET_KEY_ALREADY_EXISTS");
        assertThat(rejection.path("message").asText()).contains("target_key 'primary'");
        assertThat(targetRepository.findByModelIdOrderByTargetKeyAsc(fixture.modelId())).hasSize(1);
    }

    @Test
    void credentialShapedConnectionConfigRefIsRejectedWithoutWritingATarget() throws Exception {
        Fixture fixture = fixture("credential-target-rejection");
        long targetsBefore = targetRepository.count();

        JsonNode rejection = createTarget(fixture, "unsafe", "Unsafe", "unsafe_database",
                "postgresql://alice:secret@db.example.test/orders");

        assertThat(rejection.path("outcome").asText()).isEqualTo("REJECTED");
        assertThat(rejection.path("code").asText()).isEqualTo("UNSAFE_TARGET_CONFIGURATION");
        assertThat(rejection.path("message").asText()).contains("connection_config_ref");
        assertThat(rejection.toString()).doesNotContain("alice:secret");
        assertThat(targetRepository.count()).isEqualTo(targetsBefore);
        assertThat(targetRepository.findByModelIdOrderByTargetKeyAsc(fixture.modelId())).isEmpty();
    }

    @Test
    void partialAndIncompleteRunsNeverReturnZipBytes() throws Exception {
        Fixture fixture = fixture("incomplete-artifacts");
        JsonNode target = createTarget(fixture, "partial", "Partial", "partial_database", null).path("result");
        GenerationRunStart partial = identityService.startRun(UUID.fromString(target.path("targetId").asText()),
                fixture.version().getId(), fixture.profile().getId(), fixture.template().getId());

        MvcResult partialResponse = requestArtifacts(fixture.projectId(), partial.run().getId());
        assertIncompleteArtifactResponse(partialResponse);

        JsonNode incompleteTarget = createTarget(fixture, "incomplete", "Incomplete", "incomplete_database", null)
                .path("result");
        GenerationRunStart incompleteStart = identityService.startRun(
                UUID.fromString(incompleteTarget.path("targetId").asText()), fixture.version().getId(),
                fixture.profile().getId(), fixture.template().getId());
        GenerationRun incomplete = incompleteStart.run();
        incomplete.setState(GenerationState.SUCCEEDED);
        incomplete.setArtifactSetHash("a".repeat(64));
        incomplete.setArtifactZipHash("b".repeat(64));
        incomplete.setFinishedAt(OffsetDateTime.now());
        runRepository.saveAndFlush(incomplete);

        MvcResult incompleteResponse = requestArtifacts(fixture.projectId(), incomplete.getId());
        assertIncompleteArtifactResponse(incompleteResponse);
        assertThat(incompleteResponse.getResponse().getContentAsByteArray()).isNotEmpty();
        assertThat(targetRepository.findByModelIdOrderByTargetKeyAsc(fixture.modelId())).hasSize(2);
    }

    @Test
    void artifactServiceReturnsEmptyForPendingRunEvenWhenArtifactsAreComplete() throws Exception {
        Fixture fixture = fixture("service-download-pending");
        GeneratedRun generated = generatedRun(fixture, "pending");
        GenerationRun run = generated.run();
        run.setState(GenerationState.PENDING);
        runRepository.saveAndFlush(run);

        assertPersistedGuardInputs(run.getId(), GenerationState.PENDING,
                generated.bundle().artifactSetHash(), generated.bundle().artifactZipHash());
        assertThat(artifactService.downloadComplete(run.getId(), generated.snapshot())).isEmpty();
    }

    @Test
    void artifactServiceReturnsEmptyForFailedRunEvenWhenArtifactsAreComplete() throws Exception {
        Fixture fixture = fixture("service-download-failed");
        GeneratedRun generated = generatedRun(fixture, "failed");
        GenerationRun run = generated.run();
        run.setState(GenerationState.FAILED);
        runRepository.saveAndFlush(run);

        assertPersistedGuardInputs(run.getId(), GenerationState.FAILED,
                generated.bundle().artifactSetHash(), generated.bundle().artifactZipHash());
        assertThat(artifactService.downloadComplete(run.getId(), generated.snapshot())).isEmpty();
    }

    @Test
    void artifactServiceReturnsEmptyForSucceededRunWithoutArtifactSetHash() throws Exception {
        Fixture fixture = fixture("service-download-missing-set-hash");
        GeneratedRun generated = generatedRun(fixture, "missing-set-hash");
        GenerationRun run = generated.run();
        run.setArtifactSetHash(null);
        runRepository.saveAndFlush(run);

        assertPersistedGuardInputs(run.getId(), GenerationState.SUCCEEDED, null,
                generated.bundle().artifactZipHash());
        assertThat(artifactService.downloadComplete(run.getId(), generated.snapshot())).isEmpty();
    }

    @Test
    void artifactServiceReturnsEmptyForSucceededRunWithoutArtifactZipHash() throws Exception {
        Fixture fixture = fixture("service-download-missing-zip-hash");
        GeneratedRun generated = generatedRun(fixture, "missing-zip-hash");
        GenerationRun run = generated.run();
        run.setArtifactZipHash(null);
        runRepository.saveAndFlush(run);

        assertPersistedGuardInputs(run.getId(), GenerationState.SUCCEEDED,
                generated.bundle().artifactSetHash(), null);
        assertThat(artifactService.downloadComplete(run.getId(), generated.snapshot())).isEmpty();
    }

    @Test
    void sameGenerationRequestReturnsExistingRunWithoutCreatingAnother() throws Exception {
        Fixture fixture = fixture("idempotent-generation");
        JsonNode target = createTarget(fixture, "main", "Main", "main_database", null).path("result");
        Map<String, Object> request = generationRequest(fixture, target.path("targetId").asText(),
                UUID.randomUUID().toString());

        JsonNode first = postGeneration(fixture.projectId(), request);
        JsonNode repeated = postGeneration(fixture.projectId(), request);

        String firstRunId = first.at("/result/runId").asText();
        assertThat(first.path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(first.at("/result/state").asText()).isEqualTo("SUCCEEDED");
        assertThat(repeated.path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(repeated.at("/result/runId").asText()).isEqualTo(firstRunId);
        assertThat(repeated.path("idempotencyKey").asText()).isEqualTo(request.get("idempotencyKey"));
        assertThat(runRepository.countByTargetId(UUID.fromString(target.path("targetId").asText()))).isEqualTo(1);

        JsonNode run = getJson("/projects/{projectId}/generations/{runId}", fixture.projectId(), firstRunId);
        JsonNode details = run.path("result");
        assertThat(run.path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(details.path("runId").asText()).isEqualTo(firstRunId);
        assertThat(details.path("modelId").asText()).isEqualTo(fixture.modelId().toString());
        assertThat(details.path("versionId").asText()).isEqualTo(fixture.version().getId().toString());
        assertThat(details.path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(details.path("inputSnapshotHash").asText()).hasSize(64);
        assertThat(details.path("artifactSetHash").asText()).hasSize(64);
        assertThat(details.path("artifactZipHash").asText()).hasSize(64);
        assertThat(details.path("diagnostics").isArray()).isTrue();
        assertThat(details.path("verification").path("status").asText()).isEqualTo("NOT_RUN");
    }

    @Test
    void differentTargetsOnOneModelCreateIndependentRunsAndDownloadTheirOwnArchives() throws Exception {
        Fixture fixture = fixture("two-target-downloads");
        JsonNode primary = createTarget(fixture, "primary", "Primary", "primary_database", null).path("result");
        JsonNode reporting = createTarget(fixture, "reporting", "Reporting", "reporting_database", null)
                .path("result");
        assertThat(primary.path("targetId").asText()).isNotEqualTo(reporting.path("targetId").asText());
        assertThat(primary.path("targetKey").asText()).isNotEqualTo(reporting.path("targetKey").asText());
        assertThat(primary.path("databaseName").asText()).isNotEqualTo(reporting.path("databaseName").asText());

        JsonNode primaryRun = postGeneration(fixture.projectId(), generationRequest(fixture,
                primary.path("targetId").asText(), "key-primary-" + UUID.randomUUID()));
        JsonNode reportingRun = postGeneration(fixture.projectId(), generationRequest(fixture,
                reporting.path("targetId").asText(), "key-reporting-" + UUID.randomUUID()));
        UUID primaryRunId = UUID.fromString(primaryRun.at("/result/runId").asText());
        UUID reportingRunId = UUID.fromString(reportingRun.at("/result/runId").asText());
        assertThat(primaryRunId).isNotEqualTo(reportingRunId);

        byte[] primaryArchive = download(fixture.projectId(), primaryRunId);
        byte[] reportingArchive = download(fixture.projectId(), reportingRunId);
        String primaryProvenance = zipEntry(primaryArchive, "provenance.json");
        String reportingProvenance = zipEntry(reportingArchive, "provenance.json");
        assertThat(primaryProvenance).contains(primary.path("targetId").asText(), "\"targetKey\":\"primary\"");
        assertThat(primaryProvenance).doesNotContain(reporting.path("targetId").asText());
        assertThat(reportingProvenance).contains(reporting.path("targetId").asText(), "\"targetKey\":\"reporting\"");
        assertThat(reportingProvenance).doesNotContain(primary.path("targetId").asText());
        assertThat(primaryArchive).isNotEqualTo(reportingArchive);
        System.out.printf("T9_D14 primaryRunId=%s primaryArchiveBytes=%d reportingRunId=%s "
                        + "reportingArchiveBytes=%d%n",
                primaryRunId, primaryArchive.length, reportingRunId, reportingArchive.length);
    }

    @Test
    void unknownRunUsesRejectionBodyAndUnknownProjectUsesHouseErrorShape() throws Exception {
        Fixture fixture = fixture("unknown-run-and-project");
        JsonNode unknownRun = getJson("/projects/{projectId}/generations/{runId}", fixture.projectId(),
                UUID.randomUUID());
        assertThat(unknownRun.path("outcome").asText()).isEqualTo("REJECTED");
        assertThat(unknownRun.path("code").asText()).isEqualTo("RUN_NOT_FOUND");
        assertThat(unknownRun.path("result").isNull()).isTrue();

        MvcResult unknownProject = mockMvc.perform(get("/projects/{projectId}/generation-targets", UUID.randomUUID())
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andReturn();
        JsonNode error = objectMapper.readTree(unknownProject.getResponse().getContentAsByteArray());
        assertThat(error.path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(error.path("message").asText()).isEqualTo("Project was not found.");
        assertThat(error.path("details").isArray()).isTrue();
        assertThat(error.has("operationId")).isTrue();
    }

    @Test
    void unknownProjectsUseHouseNotFoundShapeForModelAndProfileDiscovery() throws Exception {
        UUID unknownProjectId = UUID.randomUUID();

        for (String path : List.of("/projects/{projectId}/model", "/projects/{projectId}/generation-profiles")) {
            MvcResult response = mockMvc.perform(get(path, unknownProjectId).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andReturn();
            JsonNode error = objectMapper.readTree(response.getResponse().getContentAsByteArray());
            assertThat(error.path("code").asText()).isEqualTo("NOT_FOUND");
            assertThat(error.path("message").asText()).isEqualTo("Project was not found.");
            assertThat(error.path("details").isArray()).isTrue();
            assertThat(error.has("operationId")).isTrue();
        }
    }

    @Test
    void verificationResultsStayAttachedToTheRequestedRun() throws Exception {
        Fixture fixture = fixture("verification-run-isolation");
        JsonNode primary = createTarget(fixture, "primary", "Primary", "primary_database", null).path("result");
        JsonNode reporting = createTarget(fixture, "reporting", "Reporting", "reporting_database", null)
                .path("result");
        UUID primaryRunId = UUID.fromString(postGeneration(fixture.projectId(), generationRequest(fixture,
                primary.path("targetId").asText(), "verify-primary-" + UUID.randomUUID()))
                .at("/result/runId").asText());
        UUID reportingRunId = UUID.fromString(postGeneration(fixture.projectId(), generationRequest(fixture,
                reporting.path("targetId").asText(), "verify-reporting-" + UUID.randomUUID()))
                .at("/result/runId").asText());

        JsonNode primaryVerification = postVerification(fixture.projectId(), primaryRunId);
        assertThat(primaryVerification.path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(primaryVerification.at("/result/runId").asText()).isEqualTo(primaryRunId.toString());
        assertThat(verificationRepository.findByRunIdOrderByCreatedAtAsc(primaryRunId)).isNotEmpty()
                .allSatisfy(result -> assertThat(result.getRun().getId()).isEqualTo(primaryRunId));
        assertThat(verificationRepository.findByRunIdOrderByCreatedAtAsc(reportingRunId)).isEmpty();

        JsonNode reportingVerification = postVerification(fixture.projectId(), reportingRunId);
        assertThat(reportingVerification.at("/result/runId").asText()).isEqualTo(reportingRunId.toString());
        assertThat(verificationRepository.findByRunIdOrderByCreatedAtAsc(primaryRunId)).allSatisfy(
                result -> assertThat(result.getRun().getId()).isEqualTo(primaryRunId));
        assertThat(verificationRepository.findByRunIdOrderByCreatedAtAsc(reportingRunId)).isNotEmpty()
                .allSatisfy(result -> assertThat(result.getRun().getId()).isEqualTo(reportingRunId));
    }

    @Test
    void generationRequestRequiresExplicitVersionProfileAndTemplateIdentities() throws Exception {
        Fixture fixture = fixture("required-generation-identities");
        JsonNode target = createTarget(fixture, "main", "Main", "main_database", null).path("result");
        Map<String, Object> completeRequest = generationRequest(fixture, target.path("targetId").asText(),
                "required-identities-" + UUID.randomUUID());

        for (String identity : List.of("versionId", "profileId", "templateId")) {
            Map<String, Object> request = new HashMap<>(completeRequest);
            request.remove(identity);
            MvcResult response = mockMvc.perform(post("/projects/{projectId}/generations", fixture.projectId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(request)))
                    .andExpect(status().isBadRequest())
                    .andReturn();
            assertThat(objectMapper.readTree(response.getResponse().getContentAsByteArray())
                    .path("code").asText()).isEqualTo("INVALID_PAYLOAD");
        }
        assertThat(runRepository.countByTargetId(UUID.fromString(target.path("targetId").asText()))).isZero();
    }

    @Test
    void malformedBodiesAndMissingIdempotencyKeyRemainTransportErrors() throws Exception {
        UUID unknownProjectId = UUID.randomUUID();
        MvcResult malformed = mockMvc.perform(post("/projects/{projectId}/generation-targets", unknownProjectId)
                .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(objectMapper.readTree(malformed.getResponse().getContentAsByteArray())
                .path("code").asText()).isEqualTo("INVALID_PAYLOAD");

        Fixture fixture = fixture("missing-idempotency-key");
        JsonNode target = createTarget(fixture, "main", "Main", "main_database", null).path("result");
        Map<String, Object> request = new HashMap<>(
                generationRequest(fixture, target.path("targetId").asText(), "ignored"));
        request.remove("idempotencyKey");
        MvcResult missingKey = mockMvc.perform(post("/projects/{projectId}/generations", fixture.projectId())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(objectMapper.readTree(missingKey.getResponse().getContentAsByteArray())
                .path("code").asText()).isEqualTo("INVALID_PAYLOAD");
    }

    private GeneratedRun generatedRun(Fixture fixture, String key) throws Exception {
        JsonNode target = createTarget(fixture, key, key, key + "_database", null).path("result");
        GenerationRunStart start = identityService.startRun(UUID.fromString(target.path("targetId").asText()),
                fixture.version().getId(), fixture.profile().getId(), fixture.template().getId());
        assertThat(start.run().getState()).isEqualTo(GenerationState.PENDING);

        InputSnapshot snapshot = snapshotFactory.create(fixture.version());
        GeneratedArtifactBundle bundle = artifactService.generate(start.run().getId(), snapshot);
        GenerationRun run = runRepository.findById(start.run().getId()).orElseThrow();
        assertThat(run.getState()).isEqualTo(GenerationState.SUCCEEDED);
        assertThat(run.getArtifactSetHash()).isEqualTo(bundle.artifactSetHash());
        assertThat(run.getArtifactZipHash()).isEqualTo(bundle.artifactZipHash());
        return new GeneratedRun(run, snapshot, bundle);
    }

    private void assertPersistedGuardInputs(UUID runId, GenerationState state, String artifactSetHash,
            String artifactZipHash) {
        GenerationRun persisted = runRepository.findById(runId).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(state);
        assertThat(persisted.getArtifactSetHash()).isEqualTo(artifactSetHash);
        assertThat(persisted.getArtifactZipHash()).isEqualTo(artifactZipHash);
    }

    private Fixture fixture(String label) throws Exception {
        JsonNode project = createProject("Generation API " + label + " " + UUID.randomUUID());
        UUID projectId = UUID.fromString(project.path("id").asText());
        JsonNode model = getJson("/projects/{projectId}/model", projectId);
        UUID modelId = UUID.fromString(model.path("id").asText());
        UUID classifierId = UUID.randomUUID();
        postCommand(projectId, modelId, classifierId);
        ModelVersion version = versionRepository.findTopByModelIdOrderByRevisionDesc(modelId).orElseThrow();
        GenerationProfile profile = identityService.provisionV1Profile(projectId);
        TemplateVersion template = identityService.provisionV1Template(profile.getId());
        return new Fixture(projectId, modelId, version, profile, template);
    }

    private JsonNode createProject(String name) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private void postCommand(UUID projectId, UUID modelId, UUID classifierId) throws Exception {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("operationId", UUID.randomUUID());
        envelope.put("type", "classifier.create");
        envelope.put("author", Map.of("userId", SEED_OWNER_ID, "externalSubject", SEED_OWNER_SUBJECT));
        envelope.put("projectId", projectId);
        envelope.put("modelId", modelId);
        envelope.put("baseRevision", 1L);
        envelope.put("targets", List.of());
        envelope.put("preconditions", List.of());
        envelope.put("payload", Map.of("id", classifierId, "name", "Customer", "kind", "CLASS"));
        envelope.put("origin", "MANUAL");
        envelope.put("idempotencyKey", UUID.randomUUID().toString());
        JsonNode result = objectMapper.readTree(mockMvc.perform(post("/projects/{projectId}/commands", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(envelope)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(result.path("status").asText()).isEqualTo("ACCEPTED");
    }

    private JsonNode createTarget(Fixture fixture, String key, String name, String databaseName,
            String connectionConfigRef) throws Exception {
        return createTarget(fixture.projectId(), key, name, databaseName, connectionConfigRef);
    }

    private JsonNode createTarget(UUID projectId, String key, String name, String databaseName,
            String connectionConfigRef) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("targetKey", key);
        request.put("name", name);
        request.put("databaseEngine", "POSTGRESQL");
        request.put("databaseName", databaseName);
        request.put("databaseSchema", "public");
        request.put("connectionConfigRef", connectionConfigRef);
        return objectMapper.readTree(mockMvc.perform(post("/projects/{projectId}/generation-targets", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private Map<String, Object> generationRequest(Fixture fixture, String targetId, String idempotencyKey) {
        return Map.of(
                "modelId", fixture.modelId(),
                "versionId", fixture.version().getId(),
                "targetId", targetId,
                "profileId", fixture.profile().getId(),
                "templateId", fixture.template().getId(),
                "idempotencyKey", idempotencyKey);
    }

    private JsonNode postGeneration(UUID projectId, Map<String, Object> request) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/projects/{projectId}/generations", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private JsonNode getJson(String path, Object... variables) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(path, variables).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private MvcResult requestArtifacts(UUID projectId, UUID runId) throws Exception {
        return mockMvc.perform(get("/projects/{projectId}/generations/{runId}/artifacts", projectId, runId)
                .accept(MediaType.ALL))
                .andExpect(status().isOk())
                .andReturn();
    }

    private void assertIncompleteArtifactResponse(MvcResult response) throws Exception {
        assertThat(response.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        byte[] body = response.getResponse().getContentAsByteArray();
        assertThat(body).isNotEmpty();
        assertThat(new String(body, StandardCharsets.UTF_8)).doesNotStartWith("PK");
        JsonNode outcome = objectMapper.readTree(body);
        assertThat(outcome.path("outcome").asText()).isEqualTo("REJECTED");
        assertThat(outcome.path("code").asText()).isEqualTo("RUN_NOT_COMPLETE");
        assertThat(outcome.path("message").asText()).contains("Generation run is not complete");
    }

    private byte[] download(UUID projectId, UUID runId) throws Exception {
        MvcResult result = mockMvc.perform(get("/projects/{projectId}/generations/{runId}/artifacts", projectId, runId)
                .accept(MediaType.parseMediaType("application/zip")))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentType()).isEqualTo("application/zip");
        return result.getResponse().getContentAsByteArray();
    }

    private String zipEntry(byte[] archive, String path) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (path.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Archive does not contain " + path);
    }

    private JsonNode postVerification(UUID projectId, UUID runId) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post(
                "/projects/{projectId}/generations/{runId}/verification", projectId, runId)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private record Fixture(UUID projectId, UUID modelId, ModelVersion version, GenerationProfile profile,
            TemplateVersion template) {
    }

    private record GeneratedRun(GenerationRun run, InputSnapshot snapshot, GeneratedArtifactBundle bundle) {
    }
}
