package com.diagramador.mobile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.diagramador.api.ApiException;
import com.diagramador.domain.Deployment;
import com.diagramador.domain.DeploymentState;
import com.diagramador.domain.GenerationRun;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.UmlModel;
import com.diagramador.generation.GenerationArtifactBundleBuilder.GeneratedArtifactBundle;
import com.diagramador.generation.GenerationArtifactService;
import com.diagramador.generation.InputSnapshot;
import com.diagramador.generation.InputSnapshotFactory;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.mobile.MobileGatewayResources.OperationRequest;
import com.diagramador.persistence.DeploymentRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MobileGatewayServiceTest {
    private static final UUID PROJECT_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174010");
    private static final UUID DEPLOYMENT_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174011");
    private static final UUID RUN_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174012");
    private static final String HASH = "b".repeat(64);
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static HttpServer upstream;

    private final ObjectMapper json = new ObjectMapper();
    private MobileGatewayService service;
    private CurrentUserContext users;
    private ProjectMember member;

    @BeforeAll
    static void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 19001), 0);
        upstream.createContext("/orders", exchange -> {
            REQUESTS.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
            byte[] payload = ("DELETE".equals(exchange.getRequestMethod()) ? "{\"code\":\"VALIDATION\"}" : "{}").getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders("DELETE".equals(exchange.getRequestMethod()) ? 422 : 200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() { if (upstream != null) upstream.stop(0); }

    @BeforeEach
    void setUp() {
        DeploymentRepository deployments = mock(DeploymentRepository.class);
        UmlModelRepository models = mock(UmlModelRepository.class);
        ProjectMemberRepository members = mock(ProjectMemberRepository.class);
        GenerationArtifactService artifacts = mock(GenerationArtifactService.class);
        InputSnapshotFactory snapshots = mock(InputSnapshotFactory.class);
        users = new CurrentUserContext();
        users.set(new CurrentUser(UUID.randomUUID(), "member", "Member"));
        member = mock(ProjectMember.class);
        when(member.getStatus()).thenReturn("ACTIVE");
        when(member.getRole()).thenReturn(ProjectMemberRole.EDITOR);
        when(members.findByProjectIdAndUserId(any(), any())).thenReturn(Optional.of(member));
        MobileGatewayProperties properties = new MobileGatewayProperties();
        properties.setEnabled(true);
        properties.setUpstreamHost("127.0.0.1");
        properties.setRequestTimeoutMillis(1000);
        ProjectAuthorizationService authorization = new ProjectAuthorizationService(members, users);
        service = new MobileGatewayService(deployments, models, authorization, users, artifacts, snapshots, properties, json);

        Deployment deployment = mock(Deployment.class);
        GenerationRun run = mock(GenerationRun.class);
        UmlModel model = mock(UmlModel.class);
        Project project = mock(Project.class);
        InputSnapshot snapshot = mock(InputSnapshot.class);
        GeneratedArtifactBundle bundle = mock(GeneratedArtifactBundle.class);
        when(deployment.getState()).thenReturn(DeploymentState.UP);
        when(deployment.getRun()).thenReturn(run);
        when(deployment.getHttpHostPort()).thenReturn(19001);
        when(run.getId()).thenReturn(RUN_ID);
        when(run.getArtifactSetHash()).thenReturn(HASH);
        when(run.getModel()).thenReturn(model);
        when(model.getProject()).thenReturn(project);
        when(project.getId()).thenReturn(PROJECT_ID);
        when(snapshots.create(any())).thenReturn(snapshot);
        when(artifacts.downloadComplete(any(), any())).thenReturn(Optional.of(bundle));
        when(bundle.files()).thenReturn(Map.of("openapi.json", openApi()));
        when(deployments.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(deployment));
    }

    @Test
    void enforcesFrozenSchemaAndForwardsEachCrudOperationOnlyOnce() throws Exception {
        int before = REQUESTS.get();
        assertThatThrownBy(() -> service.forward(PROJECT_ID, DEPLOYMENT_ID, request("POST", "/orders", "{\"quantity\":\"wrong\"}", true)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_OPERATION_BODY");
        assertThatThrownBy(() -> service.forward(PROJECT_ID, DEPLOYMENT_ID, request("POST", "/orders", "{\"quantity\":1}", false)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CONFIRMATION_REQUIRED");
        assertThatThrownBy(() -> service.forward(PROJECT_ID, DEPLOYMENT_ID, request("GET", "/orders/{id}", null, true)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("UNSUPPORTED_OPERATION");
        String oversized = "x".repeat(65_600);
        assertThatThrownBy(() -> service.forward(PROJECT_ID, DEPLOYMENT_ID,
                request("POST", "/orders", "{\"quantity\":1,\"tags\":[\"" + oversized + "\"]}", true)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("OPERATION_BODY_TOO_LARGE");
        assertThat(REQUESTS.get()).isEqualTo(before);

        service.forward(PROJECT_ID, DEPLOYMENT_ID, request("GET", "/orders", null, true));
        service.forward(PROJECT_ID, DEPLOYMENT_ID, request("POST", "/orders", "{\"quantity\":1}", true));
        service.forward(PROJECT_ID, DEPLOYMENT_ID, request("PUT", "/orders/123e4567-e89b-12d3-a456-426614174099", "{\"quantity\":2}", true));
        var validation = service.forward(PROJECT_ID, DEPLOYMENT_ID,
                request("DELETE", "/orders/123e4567-e89b-12d3-a456-426614174099", null, true));
        assertThat(validation.status()).isEqualTo(422);
        assertThat(validation.body().path("code").asText()).isEqualTo("VALIDATION");
        assertThat(REQUESTS.get()).isEqualTo(before + 4);
    }

    private OperationRequest request(String method, String path, String body, boolean confirmed) throws Exception {
        return new OperationRequest(HASH, method, path, body == null ? null : json.readTree(body), confirmed);
    }

    @Test
    void additiveIntentPreparationDoesNotForwardOrRelaxLegacyWriteConfirmation() throws Exception {
        int before = REQUESTS.get();
        var prepared = service.prepareAgent(PROJECT_ID, DEPLOYMENT_ID, request("POST", "/orders", "{\"quantity\":1}", false));
        assertThat(REQUESTS.get()).isEqualTo(before);
        assertThat(prepared.target().toString()).isEqualTo("http://127.0.0.1:19001/orders");
        for (String method : java.util.List.of("POST", "PUT", "DELETE")) {
            String path = "POST".equals(method) ? "/orders" : "/orders/123e4567-e89b-12d3-a456-426614174099";
            String body = "DELETE".equals(method) ? null : "{\"quantity\":1}";
            assertThatThrownBy(() -> service.forward(PROJECT_ID, DEPLOYMENT_ID, request(method, path, body, false)))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CONFIRMATION_REQUIRED");
        }
        assertThat(REQUESTS.get()).isEqualTo(before);
        assertThat(service.dispatchAgent(prepared).status()).isEqualTo(200);
        assertThat(REQUESTS.get()).isEqualTo(before + 1);
    }

    @Test
    void additiveIntentRejectsViewerForeignDeploymentExpiredIdentityAndChangedHashBeforeNetwork() throws Exception {
        int before = REQUESTS.get();
        when(member.getRole()).thenReturn(ProjectMemberRole.VIEWER);
        assertThatThrownBy(() -> service.prepareAgent(PROJECT_ID, DEPLOYMENT_ID, request("POST", "/orders", "{\"quantity\":1}", false)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FORBIDDEN");
        service.prepareAgent(PROJECT_ID, DEPLOYMENT_ID, request("GET", "/orders", null, false));
        assertThatThrownBy(() -> service.prepareAgent(PROJECT_ID, UUID.randomUUID(), request("GET", "/orders", null, false)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("BACKEND_NOT_FOUND");
        assertThatThrownBy(() -> service.prepareAgent(PROJECT_ID, DEPLOYMENT_ID,
                new OperationRequest("c".repeat(64), "GET", "/orders", null, false)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CONTRACT_MISMATCH");
        users.clear();
        assertThatThrownBy(() -> service.prepareAgent(PROJECT_ID, DEPLOYMENT_ID, request("GET", "/orders", null, false)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("UNAUTHENTICATED");
        assertThat(REQUESTS.get()).isEqualTo(before);
    }

    private static String openApi() {
        return """
                {"openapi":"3.0.3","paths":{"/orders":{"get":{},"post":{"requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Order"}}}}}},"/orders/{id}":{"put":{"requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Order"}}}}},"delete":{}}},"components":{"schemas":{"Order":{"type":"object","required":["quantity"],"properties":{"quantity":{"type":"integer"},"customerId":{"type":"string","format":"uuid","nullable":true},"tags":{"type":"array","items":{"type":"string"}}}}}}}
                """;
    }
}
