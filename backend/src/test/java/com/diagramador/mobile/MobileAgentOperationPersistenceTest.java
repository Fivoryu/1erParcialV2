package com.diagramador.mobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.diagramador.api.ApiException;
import com.diagramador.domain.Deployment;
import com.diagramador.domain.DeploymentState;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.generation.GenerationArtifactBundleBuilder.GeneratedArtifactBundle;
import com.diagramador.generation.GenerationArtifactService;
import com.diagramador.generation.InputSnapshotFactory;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.mobile.agent.MobileAgentOperationResources.Request;
import com.diagramador.mobile.agent.MobileAgentOperationService;
import com.diagramador.mobile.agent.MobileAgentOperationStore;
import com.diagramador.persistence.DeploymentRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual persistence against only a fresh loopback-bound synthetic PostgreSQL container. */
@Testcontainers
class MobileAgentOperationPersistenceTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(5432))));
    private static final String HASH = "a".repeat(64);
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource datasource;
    private final ObjectMapper json = new ObjectMapper();
    private final UUID fixture = UUID.randomUUID();
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicBoolean committedBeforeSend = new AtomicBoolean();
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer upstream;
    private MobileAgentOperationService service;
    private MobileAgentOperationStore store;
    private CurrentUserContext users;
    private ProjectMember member;
    private MobileGatewayProperties properties;
    private UUID observedOperation;
    private boolean block;
    private boolean invalidResponse;
    private boolean oversizedResponse;
    private boolean nearLimitResponse;
    private int responseStatus = 200;

    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("diagrammer").defaultSchema("diagrammer").load().migrate();
        datasource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(datasource);
    }

    @BeforeEach void fixture() throws Exception {
        jdbc.update("INSERT INTO diagrammer.user_account(user_id,external_subject,display_name,username,password_hash) VALUES (?,?,'Fixture',?,'not-login')", fixture, fixture.toString(), fixture.toString());
        jdbc.update("INSERT INTO diagrammer.project(project_id,owner_user_id,name) VALUES (?,?,'Fixture')", fixture, fixture);
        jdbc.update("INSERT INTO diagrammer.uml_model(model_id,project_id,name) VALUES (?,?,'Fixture')", fixture, fixture);
        jdbc.update("INSERT INTO diagrammer.model_version(version_id,model_id,revision,state_hash) VALUES (?,?,1,?)", fixture, fixture, HASH);
        jdbc.update("INSERT INTO diagrammer.generation_target(target_id,model_id,target_key,name,database_name) VALUES (?,?,'fixture','Fixture','fixture')", fixture, fixture);
        jdbc.update("INSERT INTO diagrammer.generation_profile(profile_id,project_id,profile_key,name,configuration_hash) VALUES (?,?,'fixture','Fixture',?)", fixture, fixture, HASH);
        jdbc.update("INSERT INTO diagrammer.template_version(template_id,profile_id,label,content_hash) VALUES (?,?,'fixture',?)", fixture, fixture, HASH);
        jdbc.update("INSERT INTO diagrammer.generation_run(run_id,model_id,version_id,target_id,target_config_revision,profile_id,template_id,input_snapshot_hash,artifact_set_hash) VALUES (?,?,?,?,1,?,?,?,?)", fixture, fixture, fixture, fixture, fixture, fixture, HASH, HASH);
        jdbc.update("INSERT INTO diagrammer.deployment(deployment_id,run_id,target_id,http_host_port,postgres_host_port,state) VALUES (?,?,?,19002,19003,'STOPPED')", fixture, fixture, fixture);
        users = new CurrentUserContext();
        identity(fixture);
        member = mock(ProjectMember.class);
        when(member.getStatus()).thenReturn("ACTIVE");
        when(member.getRole()).thenReturn(ProjectMemberRole.EDITOR);
        var members = mock(ProjectMemberRepository.class);
        when(members.findByProjectIdAndUserId(any(), any())).thenAnswer(call -> fixture.equals(call.getArgument(0)) ? Optional.of(member) : Optional.empty());
        var authorization = new ProjectAuthorizationService(members, users);
        var deployments = mock(DeploymentRepository.class);
        var deployment = mock(Deployment.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(deployment.getState()).thenReturn(DeploymentState.UP);
        when(deployment.getHttpHostPort()).thenReturn(19002);
        when(deployment.getRun().getModel().getProject().getId()).thenReturn(fixture);
        when(deployment.getRun().getArtifactSetHash()).thenReturn(HASH);
        when(deployment.getRun().getId()).thenReturn(fixture);
        when(deployments.findById(fixture)).thenReturn(Optional.of(deployment));
        var artifacts = mock(GenerationArtifactService.class);
        var bundle = mock(GeneratedArtifactBundle.class);
        when(bundle.files()).thenReturn(Map.of("openapi.json", """
                {"openapi":"3.0.3","paths":{"/items":{"get":{},"post":{"requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Item"}}}}}},"/items/{id}":{"put":{"requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Item"}}}}},"delete":{}}},"components":{"schemas":{"Item":{"type":"object","required":["quantity"],"properties":{"quantity":{"type":"integer"}}}}}}
                """));
        when(artifacts.downloadComplete(any(), any())).thenReturn(Optional.of(bundle));
        properties = new MobileGatewayProperties();
        properties.setEnabled(true);
        properties.setRequestTimeoutMillis(3000);
        var gateway = new MobileGatewayService(deployments, mock(UmlModelRepository.class), authorization, users,
                artifacts, mock(InputSnapshotFactory.class), properties, json);
        store = new MobileAgentOperationStore(jdbc, new DataSourceTransactionManager(datasource));
        service = new MobileAgentOperationService(gateway, store, users, authorization, json);
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 19002), 0);
        upstream.createContext("/items", exchange -> {
            sends.incrementAndGet();
            if (observedOperation != null) committedBeforeSend.set("dispatching".equals(jdbc.queryForObject(
                    "SELECT state FROM diagrammer.agent_operation WHERE operation_id=?", String.class, observedOperation)));
            reached.countDown();
            try { if (block) release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] result = (invalidResponse ? "not-json" : oversizedResponse ? "\"" + "x".repeat(1_048_576) + "\""
                    : nearLimitResponse ? "\"" + "x".repeat(1_048_566) + "\"" : "{\"saved\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, result.length);
            try { exchange.getResponseBody().write(result); } finally { exchange.close(); }
        });
        upstream.start();
    }

    @AfterEach void stop() { release.countDown(); if (upstream != null) upstream.stop(0); users.clear(); }
    private void identity(UUID user) { users.set(new CurrentUser(user, "fixture", "Fixture")); }
    private Request request(UUID id, String method, int quantity, String token) throws Exception {
        String path = List.of("PUT", "DELETE").contains(method) ? "/items/" + fixture : "/items";
        return new Request(id, HASH, method, path, List.of("POST", "PUT").contains(method) ? json.readTree("{\"quantity\":" + quantity + "}") : null, token);
    }
    private String snapshot() {
        return jdbc.queryForObject("SELECT COALESCE(md5(string_agg(row_to_json(r)::text, ',' ORDER BY operation_id)), '') FROM diagrammer.agent_operation r", String.class);
    }

    @Test void committedClaimDuplicateReopenAndChangedPayloadHaveOneSender() throws Exception {
        UUID id = observedOperation = UUID.randomUUID();
        Request intent = request(id, "POST", 1, null);
        var first = service.submit(fixture, fixture, intent);
        assertThat(first.state()).isEqualTo("completed");
        assertThat(committedBeforeSend).isTrue();
        assertThat(service.submit(fixture, fixture, intent)).isEqualTo(first);
        assertThat(service.lookup(fixture, fixture, id, HASH)).isEqualTo(first);
        var reopened = new MobileAgentOperationStore(new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())), new DataSourceTransactionManager(datasource));
        assertThat(reopened.lookup(fixture, fixture, fixture, id).state()).isEqualTo("completed");
        String before = snapshot();
        assertThatThrownBy(() -> service.submit(fixture, fixture, request(id, "POST", 2, null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("OPERATION_CONFLICT");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(sends).hasValue(1);
    }

    @Test void concurrentDuplicatesAndChangedPayloadDuringSendNeverRedispatchOrHoldTheLock() throws Exception {
        UUID id = observedOperation = UUID.randomUUID();
        Request intent = request(id, "POST", 1, null);
        block = true;
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> { identity(fixture); return service.submit(fixture, fixture, intent); });
            assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue();
            var duplicate = service.submit(fixture, fixture, intent);
            assertThat(duplicate.state()).isEqualTo("dispatching");
            String before = snapshot();
            assertThatThrownBy(() -> service.submit(fixture, fixture, request(id, "POST", 2, null))).isInstanceOf(ApiException.class);
            assertThat(snapshot()).isEqualTo(before);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).state()).isEqualTo("completed");
            assertThat(sends).hasValue(1);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void deletionRequiresExactPersistedPreviewAndConfirmation() throws Exception {
        UUID id = UUID.randomUUID();
        var preview = service.submit(fixture, fixture, request(id, "DELETE", 0, null));
        assertThat(preview.state()).isEqualTo("awaiting_confirmation");
        assertThat(preview.path()).isEqualTo("/items/" + fixture);
        assertThat(preview.confirmationToken()).hasSize(64);
        assertThat(sends).hasValue(0);
        String before = snapshot();
        assertThatThrownBy(() -> service.submit(fixture, fixture, request(id, "DELETE", 0, "wrong"))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
        UUID foreign = UUID.randomUUID();
        assertThatThrownBy(() -> service.submit(fixture, fixture, request(foreign, "DELETE", 0, preview.confirmationToken()))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
        var confirmed = service.submit(fixture, fixture, request(id, "DELETE", 0, preview.confirmationToken()));
        assertThat(confirmed.state()).isEqualTo("completed");
        assertThat(service.submit(fixture, fixture, request(id, "DELETE", 0, preview.confirmationToken()))).isEqualTo(confirmed);
        assertThat(sends).hasValue(1);
    }

    @Test void foreignViewerExpiredAndChangedContractRejectionsLeavePersistenceAndNetworkUnchanged() throws Exception {
        UUID id = UUID.randomUUID();
        service.submit(fixture, fixture, request(id, "POST", 1, null));
        int beforeSends = sends.get();
        String before = snapshot();
        identity(UUID.randomUUID());
        assertThatThrownBy(() -> service.lookup(fixture, fixture, id, HASH)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("OPERATION_NOT_FOUND");
        identity(fixture);
        when(member.getRole()).thenReturn(ProjectMemberRole.VIEWER);
        assertThatThrownBy(() -> service.lookup(fixture, fixture, id, HASH)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> service.submit(fixture, fixture, request(UUID.randomUUID(), "PUT", 2, null))).isInstanceOf(ApiException.class);
        when(member.getRole()).thenReturn(ProjectMemberRole.EDITOR);
        assertThatThrownBy(() -> service.lookup(fixture, fixture, id, "b".repeat(64))).isInstanceOf(ApiException.class).extracting("code").isEqualTo("CONTRACT_MISMATCH");
        assertThatThrownBy(() -> service.submit(UUID.randomUUID(), fixture, request(UUID.randomUUID(), "GET", 0, null))).isInstanceOf(ApiException.class);
        users.clear();
        assertThatThrownBy(() -> service.lookup(fixture, fixture, id, HASH)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("UNAUTHENTICATED");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(sends).hasValue(beforeSends);
    }

    @Test void unknownAndCrashWindowDispatchingRemainNonRetryable() throws Exception {
        UUID id = observedOperation = UUID.randomUUID();
        invalidResponse = true;
        var unknown = service.submit(fixture, fixture, request(id, "POST", 1, null));
        assertThat(unknown.state()).isEqualTo("unknown");
        assertThat(service.submit(fixture, fixture, request(id, "POST", 1, null))).isEqualTo(unknown);
        UUID crash = UUID.randomUUID();
        Request pending = request(crash, "POST", 2, null);
        store.claim(fixture, fixture, fixture, pending, com.diagramador.mobile.agent.MobileAgentOperationResources.canonical(json, pending));
        assertThat(service.submit(fixture, fixture, pending).state()).isEqualTo("dispatching");
        assertThat(service.lookup(fixture, fixture, crash, HASH).state()).isEqualTo("dispatching");
        assertThat(sends).hasValue(1);
    }

    @Test void oversizedUpstreamIsUnknownAndViewerReadReceiptsRemainAccessible() throws Exception {
        UUID id = UUID.randomUUID();
        oversizedResponse = true;
        var unknown = service.submit(fixture, fixture, request(id, "GET", 0, null));
        assertThat(unknown.state()).isEqualTo("unknown");
        assertThat(unknown.error()).isEqualTo("UPSTREAM_RESPONSE_TOO_LARGE");
        when(member.getRole()).thenReturn(ProjectMemberRole.VIEWER);
        assertThat(service.lookup(fixture, fixture, id, HASH)).isEqualTo(unknown);
        assertThat(service.submit(fixture, fixture, request(id, "GET", 0, null))).isEqualTo(unknown);
        assertThat(sends).hasValue(1);
    }

    @Test void absentConcurrentClaimsElectExactlyOneSenderAndPersistTheOtherAsDuplicate() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Request intent = request(UUID.randomUUID(), "POST", 1, null);
        String canonical = com.diagramador.mobile.agent.MobileAgentOperationResources.canonical(json, intent);
        java.util.concurrent.Callable<Boolean> claim = () -> {
            start.await();
            return store.claim(fixture, fixture, fixture, intent, canonical).dispatch();
        };
        try {
            var a = pool.submit(claim); var b = pool.submit(claim); start.countDown();
            assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            assertThat(service.submit(fixture, fixture, intent).state()).isEqualTo("dispatching");
            assertThat(sends).hasValue(0);
        } finally { pool.shutdownNow(); }
    }

    @Test void invalidSchemaArbitraryUrlCollectionDeleteChangedPreviewAndInapplicableTokenHaveZeroEffect() throws Exception {
        String before = snapshot();
        Request invalid = new Request(UUID.randomUUID(), HASH, "POST", "/items", json.readTree("{\"quantity\":\"wrong\"}"), null);
        assertThatThrownBy(() -> service.submit(fixture, fixture, invalid)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.submit(fixture, fixture, new Request(UUID.randomUUID(), HASH, "GET", "http://evil", null, null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.submit(fixture, fixture, new Request(UUID.randomUUID(), HASH, "DELETE", "/items", null, null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("DELETE_TARGET_REQUIRED");
        assertThatThrownBy(() -> service.submit(fixture, fixture, request(UUID.randomUUID(), "POST", 1, "ignored")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CONFIRMATION_NOT_APPLICABLE");
        assertThat(snapshot()).isEqualTo(before);
        UUID id = UUID.randomUUID();
        var preview = service.submit(fixture, fixture, request(id, "DELETE", 0, null));
        before = snapshot();
        var changed = new Request(id, HASH, "DELETE", "/items/" + UUID.randomUUID(), null, preview.confirmationToken());
        assertThatThrownBy(() -> service.submit(fixture, fixture, changed)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("OPERATION_CONFLICT");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(sends).hasValue(0);
    }

    @Test void knownUpstreamRejectionIsCompletedAndNeverRedispatched() throws Exception {
        responseStatus = 422;
        Request intent = request(UUID.randomUUID(), "PUT", 2, null);
        var result = service.submit(fixture, fixture, intent);
        assertThat(result.state()).isEqualTo("completed");
        assertThat(result.status()).isEqualTo(422);
        assertThat(service.submit(fixture, fixture, intent)).isEqualTo(result);
        assertThat(sends).hasValue(1);
    }

    @Test void completeReceiptEnvelopeIncludingIntentAndResultCannotExceedOneMiB() throws Exception {
        nearLimitResponse = true;
        Request intent = request(UUID.randomUUID(), "POST", 1, null);
        var receipt = service.submit(fixture, fixture, intent);
        assertThat(receipt.state()).isEqualTo("unknown");
        assertThat(receipt.error()).isEqualTo("UPSTREAM_RESPONSE_TOO_LARGE");
        assertThat(json.writeValueAsBytes(receipt).length).isLessThanOrEqualTo(1_048_576);
        assertThat(service.submit(fixture, fixture, intent)).isEqualTo(receipt);
        assertThat(sends).hasValue(1);
    }
}
