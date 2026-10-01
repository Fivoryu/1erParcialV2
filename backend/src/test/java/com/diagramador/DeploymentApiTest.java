package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramador.domain.Deployment;
import com.diagramador.domain.DeploymentState;
import com.diagramador.domain.GenerationProfile;
import com.diagramador.domain.GenerationRun;
import com.diagramador.domain.GenerationTarget;
import com.diagramador.domain.ModelVersion;
import com.diagramador.domain.TemplateVersion;
import com.diagramador.generation.GenerationArtifactService;
import com.diagramador.generation.GenerationIdentityService;
import com.diagramador.generation.GenerationIdentityService.GenerationRunStart;
import com.diagramador.generation.GenerationIdentityService.TargetConfiguration;
import com.diagramador.generation.InputSnapshot;
import com.diagramador.generation.InputSnapshotFactory;
import com.diagramador.persistence.DeploymentRepository;
import com.diagramador.persistence.GenerationRunRepository;
import com.diagramador.persistence.ModelVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The deployment API: ordering a frozen run, claiming it once, reporting what happened, and stopping it.
 *
 * <p>Every assertion is about what a client can observe or what the row holds. The state machine, the port
 * assignment and the refusal to store a credential are all exercised here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeploymentApiTest extends AuthenticatedApiTest {

    private static final String OPERATOR_TOKEN = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void operatorProperties(DynamicPropertyRegistry registry) {
        registry.add("diagrammer.operator.token", () -> OPERATOR_TOKEN);
    }

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final GenerationIdentityService identityService;
    private final GenerationArtifactService artifactService;
    private final InputSnapshotFactory snapshotFactory;
    private final ModelVersionRepository versionRepository;
    private final GenerationRunRepository runRepository;
    private final DeploymentRepository deploymentRepository;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    DeploymentApiTest(MockMvc mockMvc, ObjectMapper objectMapper, GenerationIdentityService identityService,
            GenerationArtifactService artifactService, InputSnapshotFactory snapshotFactory,
            ModelVersionRepository versionRepository, GenerationRunRepository runRepository,
            DeploymentRepository deploymentRepository, JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.identityService = identityService;
        this.artifactService = artifactService;
        this.snapshotFactory = snapshotFactory;
        this.versionRepository = versionRepository;
        this.runRepository = runRepository;
        this.deploymentRepository = deploymentRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Releases every active deployment after each test.
     *
     * <p>The port pair is host-global, so a deployment left active by one test would push the next test to the
     * second pair and make the convention untestable. Only the active states hold a port, which is why moving
     * them all to stopped is enough.
     */
    @AfterEach
    void releaseActiveDeployments() {
        jdbcTemplate.update("UPDATE diagrammer.deployment SET state = 'STOPPED', updated_at = now() "
                + "WHERE state <> 'STOPPED'");
    }

    @Test
    void orderingAFinishedRunReservesTheFirstPortPairOfTheConvention() throws Exception {
        Fixture fixture = fixture("first-pair");
        GenerationRun run = finishedRun(fixture, "reporting");

        JsonNode deployment = order(fixture.projectId(), run.getId(), 201);

        assertThat(deployment.path("state").asText()).isEqualTo("PENDING");
        assertThat(deployment.path("httpHostPort").asInt()).isEqualTo(19001);
        assertThat(deployment.path("postgresHostPort").asInt()).isEqualTo(25433);
        assertThat(deployment.path("targetKey").asText()).startsWith("reporting-");
        assertThat(deployment.path("composeProject").isNull()).isTrue();
        assertThat(deployment.path("failureMessage").isNull()).isTrue();
    }

    @Test
    void orderingAnUnfinishedRunNamesTheReasonAndTheState() throws Exception {
        Fixture fixture = fixture("unfinished");
        GenerationRun run = pendingRun(fixture, "halfway");

        JsonNode error = orderAndReadError(fixture.projectId(), run.getId(), 409);

        assertThat(error.path("code").asText()).isEqualTo("RUN_NOT_COMPLETE");
        assertThat(error.path("message").asText()).contains("finished with an archive");
        assertThat(error.path("details").get(0).path("state").asText()).isEqualTo("PENDING");
    }

    @Test
    void orderingARunOfAnotherProjectIsRejected() throws Exception {
        Fixture mine = fixture("mine");
        Fixture other = fixture("other");
        GenerationRun foreign = finishedRun(other, "foreign");

        JsonNode error = orderAndReadError(mine.projectId(), foreign.getId(), 409);

        assertThat(error.path("code").asText()).isEqualTo("RUN_NOT_IN_PROJECT");
    }

    @Test
    void aDestinationHoldsOneActiveDeploymentAtATime() throws Exception {
        Fixture fixture = fixture("one-active");
        GenerationRun first = finishedRun(fixture, "reporting");
        JsonNode ordered = order(fixture.projectId(), first.getId(), 201);

        JsonNode error = orderAndReadError(fixture.projectId(), first.getId(), 409);

        assertThat(error.path("code").asText()).isEqualTo("DEPLOYMENT_ALREADY_ACTIVE");
        assertThat(error.path("details").get(0).path("deploymentId").asText())
                .isEqualTo(ordered.path("deploymentId").asText());
    }

    @Test
    void twoDestinationsGetTwoDifferentPortPairs() throws Exception {
        Fixture fixture = fixture("two-pairs");
        GenerationRun reporting = finishedRun(fixture, "reporting");
        GenerationRun billing = finishedRun(fixture, "billing");

        JsonNode first = order(fixture.projectId(), reporting.getId(), 201);
        JsonNode second = order(fixture.projectId(), billing.getId(), 201);

        assertThat(first.path("httpHostPort").asInt()).isEqualTo(19001);
        assertThat(second.path("httpHostPort").asInt()).isEqualTo(19002);
        assertThat(first.path("postgresHostPort").asInt()).isEqualTo(25433);
        assertThat(second.path("postgresHostPort").asInt()).isEqualTo(25434);
    }

    /**
     * Eight operators race on one pending order, and exactly one wins.
     *
     * <p>Two sequential claims only prove that a taken order cannot be taken again. The property this harness
     * depends on is stronger: the state is checked inside the {@code UPDATE}, so every operator that read the
     * pending row still reaches the database and only one of them changes it. Replacing that conditional update
     * with a read followed by a write makes this test fail with more than one winner.
     */
    @Test
    void manyOperatorsRacingOnOneOrderProduceExactlyOneWinner() throws Exception {
        Fixture fixture = fixture("race");
        JsonNode deployment = order(fixture.projectId(), finishedRun(fixture, "reporting").getId(), 201);
        String id = deployment.path("deploymentId").asText();
        int racers = 8;

        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> attempts = new java.util.ArrayList<>();
        try {
            for (int index = 0; index < racers; index++) {
                String operatorId = "host-" + index + ":1";
                attempts.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(withOperator(post("/deployments/{id}/claim", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(Map.of("operatorId", operatorId)))))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (Future<Integer> attempt : attempts) {
                statuses.add(attempt.get(60, TimeUnit.SECONDS));
            }
            assertThat(statuses).as("exactly one claim is accepted").filteredOn(code -> code == 200).hasSize(1);
            assertThat(statuses).as("every other claim is refused as already claimed")
                    .filteredOn(code -> code == 409).hasSize(racers - 1);
        } finally {
            pool.shutdownNow();
        }

        Deployment persisted = deploymentRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(DeploymentState.RUNNING);
        assertThat(persisted.getOperatorId()).isNotBlank();
    }

    @Test
    void onlyOneOfTwoClaimsWinsAndTheWinnerIsRecorded() throws Exception {
        Fixture fixture = fixture("one-winner");
        JsonNode deployment = order(fixture.projectId(), finishedRun(fixture, "reporting").getId(), 201);
        String id = deployment.path("deploymentId").asText();

        JsonNode winner = claim(id, "host-a:1", 200);
        JsonNode loser = claimAndReadError(id, "host-b:2", 409);

        assertThat(winner.path("state").asText()).isEqualTo("RUNNING");
        assertThat(winner.path("operatorId").asText()).isEqualTo("host-a:1");
        assertThat(loser.path("code").asText()).isEqualTo("DEPLOYMENT_ALREADY_CLAIMED");
        assertThat(loser.path("details").get(0).path("operatorId").asText()).isEqualTo("host-a:1");
        assertThat(deploymentRepository.findById(UUID.fromString(id)).orElseThrow().getOperatorId())
                .isEqualTo("host-a:1");
    }

    @Test
    void reportingUpStoresTheComposeProjectAndTheState() throws Exception {
        String id = claimedDeployment("reporting-up");

        JsonNode reported = report(id, Map.of("outcome", "UP",
                "composeProject", "generated-reporting-service-t0abcde"), 200);

        assertThat(reported.path("state").asText()).isEqualTo("UP");
        assertThat(reported.path("composeProject").asText()).isEqualTo("generated-reporting-service-t0abcde");
        assertThat(reported.path("finishedAt").isNull()).isFalse();
    }

    @Test
    void reportingAFailureStoresTheReason() throws Exception {
        String id = claimedDeployment("failed");

        JsonNode reported = report(id, Map.of("outcome", "FAILED", "failureCode", "COMPOSE_UP_FAILED",
                "failureMessage", "The image build failed before the database was ready."), 200);

        assertThat(reported.path("state").asText()).isEqualTo("FAILED");
        assertThat(reported.path("failureCode").asText()).isEqualTo("COMPOSE_UP_FAILED");
        assertThat(reported.path("failureMessage").asText()).contains("image build failed");
    }

    @Test
    void aCredentialShapedFailureMessageIsRejectedAndTheRowIsUntouched() throws Exception {
        String id = claimedDeployment("unsafe-message");

        JsonNode error = reportAndReadError(id, Map.of("outcome", "FAILED", "failureCode", "COMPOSE_UP_FAILED",
                "failureMessage", "Connect failed with password=sup3r-s3cret for user generated_user"), 400);

        assertThat(error.path("code").asText()).isEqualTo("UNSAFE_FAILURE_MESSAGE");
        var persisted = deploymentRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(DeploymentState.RUNNING);
        assertThat(persisted.getFailureMessage()).isNull();
    }

    @Test
    void aComposeProjectThatLooksLikeAPathIsRejected() throws Exception {
        String id = claimedDeployment("unsafe-project");

        JsonNode error = reportAndReadError(id, Map.of("outcome", "UP",
                "composeProject", "../../etc/passwd"), 400);

        assertThat(error.path("code").asText()).isEqualTo("UNSAFE_COMPOSE_PROJECT");
        assertThat(error.path("message").asText()).contains("never a path");
    }

    @Test
    void anUnknownOutcomeIsRejectedNamingIt() throws Exception {
        String id = claimedDeployment("unknown-outcome");

        JsonNode error = reportAndReadError(id, Map.of("outcome", "PROBABLY_FINE"), 400);

        assertThat(error.path("code").asText()).isEqualTo("UNKNOWN_DEPLOYMENT_OUTCOME");
        assertThat(error.path("message").asText()).contains("PROBABLY_FINE");
    }

    @Test
    void anImpossibleTransitionIsRejectedWithTheCurrentState() throws Exception {
        Fixture fixture = fixture("impossible");
        JsonNode deployment = order(fixture.projectId(), finishedRun(fixture, "reporting").getId(), 201);
        String id = deployment.path("deploymentId").asText();

        JsonNode error = reportAndReadError(id, Map.of("outcome", "UP",
                "composeProject", "generated-reporting-service-t0abcde"), 409);

        assertThat(error.path("code").asText()).isEqualTo("DEPLOYMENT_STATE_CONFLICT");
        assertThat(error.path("details").get(0).path("state").asText()).isEqualTo("PENDING");
        assertThat(error.path("message").asText()).contains("PENDING").contains("UP");
    }

    @Test
    void stoppingADeploymentNobodyClaimedStopsItWithoutAnOperator() throws Exception {
        Fixture fixture = fixture("stop-pending");
        JsonNode deployment = order(fixture.projectId(), finishedRun(fixture, "reporting").getId(), 201);

        JsonNode stopped = stop(deployment.path("deploymentId").asText(), 200);

        assertThat(stopped.path("state").asText()).isEqualTo("STOPPED");
        assertThat(stopped.path("finishedAt").isNull()).isFalse();
    }

    @Test
    void stoppingAClaimedDeploymentAsksTheOperatorAndThenItEnds() throws Exception {
        String id = claimedDeployment("stop-up");
        report(id, Map.of("outcome", "UP", "composeProject", "generated-reporting-service-t0abcde"), 200);

        JsonNode stopping = stop(id, 200);
        assertThat(stopping.path("state").asText()).isEqualTo("STOPPING");

        JsonNode stopped = report(id, Map.of("outcome", "STOPPED"), 200);
        assertThat(stopped.path("state").asText()).isEqualTo("STOPPED");
        assertThat(stopped.path("finishedAt").isNull()).isFalse();
    }

    @Test
    void stoppingAStoppedDeploymentIsRejected() throws Exception {
        Fixture fixture = fixture("stop-twice");
        JsonNode deployment = order(fixture.projectId(), finishedRun(fixture, "reporting").getId(), 201);
        stop(deployment.path("deploymentId").asText(), 200);

        JsonNode error = stopAndReadError(deployment.path("deploymentId").asText(), 409);

        assertThat(error.path("code").asText()).isEqualTo("DEPLOYMENT_STATE_CONFLICT");
        assertThat(error.path("message").asText()).contains("STOPPED");
    }

    @Test
    void theListReturnsEveryDeploymentOfTheModelWithoutASecret() throws Exception {
        Fixture fixture = fixture("list");
        JsonNode ordered = order(fixture.projectId(), finishedRun(fixture, "reporting").getId(), 201);
        String id = ordered.path("deploymentId").asText();
        claim(id, "host-a:1", 200);
        report(id, Map.of("outcome", "FAILED", "failureCode", "COMPOSE_UP_FAILED",
                "failureMessage", "The migration could not be applied."), 200);

        String body = mockMvc.perform(authorized(get("/projects/{projectId}/deployments", fixture.projectId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode deployments = objectMapper.readTree(body);
        assertThat(deployments).hasSize(1);
        assertThat(deployments.get(0).path("state").asText()).isEqualTo("FAILED");
        // No field of a deployment may carry a credential or a location on the machine.
        assertThat(body).doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("secret")
                .doesNotContainIgnoringCase("token").doesNotContain("C:\\").doesNotContain("/home/")
                .doesNotContain("/Users/");
    }

    // ------------------------------------------------------------------ helpers

    private record Fixture(UUID projectId, UUID modelId, ModelVersion version, GenerationProfile profile,
            TemplateVersion template) {
    }

    private Fixture fixture(String label) throws Exception {
        JsonNode project = objectMapper.readTree(mockMvc.perform(authorized(post("/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("name", "Deployment API " + label)))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());
        UUID projectId = UUID.fromString(project.path("id").asText());
        JsonNode model = readJson(mockMvc.perform(authorized(get("/projects/{id}/model", projectId)))
                .andExpect(status().isOk())
                .andReturn());
        UUID modelId = UUID.fromString(model.path("id").asText());
        createClassifier(projectId, modelId);
        ModelVersion version = versionRepository.findTopByModelIdOrderByRevisionDesc(modelId).orElseThrow();
        GenerationProfile profile = identityService.provisionV1Profile(projectId);
        TemplateVersion template = identityService.provisionV1Template(profile.getId());
        return new Fixture(projectId, modelId, version, profile, template);
    }

    private void createClassifier(UUID projectId, UUID modelId) throws Exception {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("operationId", UUID.randomUUID());
        envelope.put("type", "classifier.create");
        envelope.put("projectId", projectId);
        envelope.put("modelId", modelId);
        envelope.put("baseRevision", 1L);
        envelope.put("targets", List.of());
        envelope.put("preconditions", List.of());
        envelope.put("payload", Map.of("id", UUID.randomUUID(), "name", "Customer", "kind", "CLASS"));
        envelope.put("origin", "MANUAL");
        envelope.put("idempotencyKey", UUID.randomUUID().toString());
        JsonNode result = readJson(mockMvc.perform(authorized(post("/projects/{projectId}/commands", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(envelope))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(result.path("status").asText()).isEqualTo("ACCEPTED");
    }

    private GenerationTarget target(Fixture fixture, String key) {
        return identityService.createTarget(fixture.modelId(), key + "-" + UUID.randomUUID().toString().substring(0, 8),
                new TargetConfiguration(key, "POSTGRESQL", key + "_db", "public", null));
    }

    /** A run that finished with an archive: the only kind a deployment can point at. */
    private GenerationRun finishedRun(Fixture fixture, String key) {
        GenerationRunStart start = startRun(fixture, key);
        InputSnapshot snapshot = snapshotFactory.create(fixture.version());
        artifactService.generate(start.run().getId(), snapshot);
        GenerationRun run = runRepository.findById(start.run().getId()).orElseThrow();
        assertThat(run.getArtifactZipHash()).isNotNull();
        return run;
    }

    /** A run nobody generated artifacts for, so it is still pending. */
    private GenerationRun pendingRun(Fixture fixture, String key) {
        return runRepository.findById(startRun(fixture, key).run().getId()).orElseThrow();
    }

    private GenerationRunStart startRun(Fixture fixture, String key) {
        GenerationTarget target = target(fixture, key);
        return identityService.startRun(target.getId(), fixture.version().getId(), fixture.profile().getId(),
                fixture.template().getId());
    }

    private JsonNode order(UUID projectId, UUID runId, int expectedStatus) throws Exception {
        return readJson(mockMvc.perform(authorized(post("/projects/{projectId}/deployments", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("runId", runId.toString())))))
                .andExpect(status()
                        .is(expectedStatus))
                .andReturn());
    }

    private JsonNode orderAndReadError(UUID projectId, UUID runId, int expectedStatus) throws Exception {
        return order(projectId, runId, expectedStatus);
    }

    private JsonNode claim(String deploymentId, String operatorId, int expectedStatus) throws Exception {
        return readJson(mockMvc.perform(withOperator(post("/deployments/{id}/claim", deploymentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("operatorId", operatorId)))))
                .andExpect(status()
                        .is(expectedStatus))
                .andReturn());
    }

    private JsonNode claimAndReadError(String deploymentId, String operatorId, int expectedStatus) throws Exception {
        return claim(deploymentId, operatorId, expectedStatus);
    }

    private JsonNode report(String deploymentId, Map<String, Object> body, int expectedStatus) throws Exception {
        return readJson(mockMvc.perform(withOperator(post("/deployments/{id}/report", deploymentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body))))
                .andExpect(status()
                        .is(expectedStatus))
                .andReturn());
    }

    private JsonNode reportAndReadError(String deploymentId, Map<String, Object> body, int expectedStatus)
            throws Exception {
        return report(deploymentId, body, expectedStatus);
    }

    private JsonNode stop(String deploymentId, int expectedStatus) throws Exception {
        return readJson(mockMvc.perform(authorized(post("/deployments/{id}/stop", deploymentId)))
                .andExpect(status()
                        .is(expectedStatus))
                .andReturn());
    }

    private JsonNode stopAndReadError(String deploymentId, int expectedStatus) throws Exception {
        return stop(deploymentId, expectedStatus);
    }

    /** Orders and claims a deployment of a fresh project, ready for a report. */
    private String claimedDeployment(String label) throws Exception {
        Fixture fixture = fixture(label);
        JsonNode deployment = order(fixture.projectId(), finishedRun(fixture, "reporting").getId(), 201);
        String id = deployment.path("deploymentId").asText();
        claim(id, "host-a:1", 200);
        return id;
    }

    private MockHttpServletRequestBuilder withOperator(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR_TOKEN);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
