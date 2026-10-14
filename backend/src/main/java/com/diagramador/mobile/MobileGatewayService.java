package com.diagramador.mobile;

import static com.diagramador.mobile.MobileGatewayResources.BackendResource;
import static com.diagramador.mobile.MobileGatewayResources.ContractResource;
import static com.diagramador.mobile.MobileGatewayResources.OperationRequest;
import static com.diagramador.mobile.MobileGatewayResources.OperationResource;

import com.diagramador.api.ApiException;
import com.diagramador.domain.Deployment;
import com.diagramador.domain.DeploymentState;
import com.diagramador.domain.GenerationRun;
import com.diagramador.generation.GenerationArtifactBundleBuilder;
import com.diagramador.generation.GenerationArtifactBundleBuilder.GeneratedArtifactBundle;
import com.diagramador.generation.GenerationArtifactService;
import com.diagramador.generation.InputSnapshotFactory;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.DeploymentRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validates a request against the frozen generated OpenAPI document before forwarding it once. */
@Service
public class MobileGatewayService {
    private static final Logger LOG = LoggerFactory.getLogger(MobileGatewayService.class);
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:]+\\]");
    private static final Pattern UUID_SEGMENT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int FIRST_GENERATED_HTTP_PORT = 19001;
    private static final int LAST_GENERATED_HTTP_PORT = 19010;
    private static final int MAX_OPERATION_BODY_BYTES = 65_536;

    private final DeploymentRepository deployments;
    private final UmlModelRepository models;
    private final ProjectAuthorizationService authorization;
    private final CurrentUserContext users;
    private final GenerationArtifactService artifacts;
    private final InputSnapshotFactory snapshots;
    private final MobileGatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    public MobileGatewayService(DeploymentRepository deployments, UmlModelRepository models, ProjectAuthorizationService authorization,
            CurrentUserContext users, GenerationArtifactService artifacts, InputSnapshotFactory snapshots,
            MobileGatewayProperties properties, ObjectMapper objectMapper) {
        this.deployments = deployments;
        this.models = models;
        this.authorization = authorization;
        this.users = users;
        this.artifacts = artifacts;
        this.snapshots = snapshots;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Transactional(readOnly = true)
    public List<BackendResource> list(UUID projectId) {
        requireMember(projectId);
        UUID modelId = models.findByProjectId(projectId).map(model -> model.getId())
                .orElseThrow(() -> rejected("BACKEND_NOT_FOUND", HttpStatus.NOT_FOUND));
        return deployments.findByModelIdOrderByRequestedAtDesc(modelId).stream()
                .filter(deployment -> deployment.getState() == DeploymentState.UP)
                .map(this::resource).toList();
    }

    @Transactional(readOnly = true)
    public ContractResource contract(UUID projectId, UUID deploymentId) {
        requireMember(projectId);
        Frozen frozen = requireFrozenUpDeployment(projectId, deploymentId);
        return new ContractResource(deploymentId, frozen.run().getId(), frozen.contractHash(), frozen.openApi());
    }

    @Transactional(readOnly = true)
    public OperationResource forward(UUID projectId, UUID deploymentId, OperationRequest request) {
        CurrentUser user = users.require(); // Operators have no user identity and may not reach generated data.
        try {
            if (isWrite(request.method())) authorization.requireEditor(projectId); else authorization.requireMember(projectId);
            Frozen frozen = requireFrozenUpDeployment(projectId, deploymentId);
            if (!frozen.contractHash().equals(request.contractHash())) throw rejected("CONTRACT_MISMATCH", HttpStatus.CONFLICT);
            validateOperation(frozen.openApi(), request);
            if (isWrite(request.method()) && !request.confirmed()) throw rejected("CONFIRMATION_REQUIRED", HttpStatus.CONFLICT);
            if (!properties.isEnabled()) throw rejected("MOBILE_GATEWAY_DISABLED", HttpStatus.SERVICE_UNAVAILABLE);
            URI target = upstreamUri(frozen.deployment().getHttpHostPort(), request.path());
            UpstreamResponse response = forwardOnce(target, request);
            audit(user, projectId, deploymentId, request.method(), request.path(), response.status());
            return new OperationResource(response.status(), response.body());
        } catch (ApiException exception) {
            audit(user, projectId, deploymentId, request.method(), request.path(), exception.status().value());
            throw exception;
        }
    }

    /** Validates structured intent and captures the operator-owned URI without contacting the upstream. */
    @Transactional(readOnly = true)
    public PreparedAgentOperation prepareAgent(UUID projectId, UUID deploymentId, OperationRequest request) {
        CurrentUser user = requireAuthenticatedMember(projectId, isWrite(request.method()));
        Frozen frozen = requireFrozenUpDeployment(projectId, deploymentId);
        if (!frozen.contractHash().equals(request.contractHash())) throw rejected("CONTRACT_MISMATCH", HttpStatus.CONFLICT);
        if (!List.of("GET", "POST", "PUT", "DELETE").contains(request.method())) throw rejected("UNSUPPORTED_OPERATION", HttpStatus.BAD_REQUEST);
        validateOperation(frozen.openApi(), request);
        if (!properties.isEnabled()) throw rejected("MOBILE_GATEWAY_DISABLED", HttpStatus.SERVICE_UNAVAILABLE);
        URI target = upstreamUri(frozen.deployment().getHttpHostPort(), request.path());
        try { upstreamRequest(target, request); }
        catch (IOException exception) { throw rejected("INVALID_OPERATION_BODY", HttpStatus.BAD_REQUEST); }
        return new PreparedAgentOperation(user.userId(), projectId, deploymentId, target, request);
    }

    /** Called only after the durable receipt claim commits; it never retries the request. */
    public OperationResource dispatchAgent(PreparedAgentOperation prepared) {
        try {
            UpstreamResponse response = forwardOnce(prepared.target(), prepared.request());
            audit(new CurrentUser(prepared.userId(), "", ""), prepared.projectId(), prepared.deploymentId(),
                    prepared.request().method(), prepared.request().path(), response.status());
            return new OperationResource(response.status(), response.body());
        } catch (ApiException failure) {
            audit(new CurrentUser(prepared.userId(), "", ""), prepared.projectId(), prepared.deploymentId(),
                    prepared.request().method(), prepared.request().path(), failure.status().value());
            throw failure;
        }
    }

    public record PreparedAgentOperation(UUID userId, UUID projectId, UUID deploymentId, URI target, OperationRequest request) { }

    private BackendResource resource(Deployment deployment) {
        Frozen frozen = frozen(deployment);
        return new BackendResource(deployment.getId(), deployment.getTarget().getTargetKey(), deployment.getState().name(),
                frozen.run().getId(), frozen.contractHash());
    }

    private Frozen requireFrozenUpDeployment(UUID projectId, UUID deploymentId) {
        Deployment deployment = deployments.findById(deploymentId).orElseThrow(() -> rejected("BACKEND_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (!projectId.equals(projectId(deployment)) || deployment.getState() != DeploymentState.UP) {
            throw rejected("BACKEND_NOT_AVAILABLE", HttpStatus.NOT_FOUND);
        }
        return frozen(deployment);
    }

    private Frozen frozen(Deployment deployment) {
        GenerationRun run = deployment.getRun();
        Optional<GeneratedArtifactBundle> bundle = artifacts.downloadComplete(run.getId(), snapshots.create(run.getVersion()));
        String openApi = bundle.map(value -> value.files().get(GenerationArtifactBundleBuilder.OPENAPI_PATH)).orElse(null);
        if (openApi == null || run.getArtifactSetHash() == null) throw rejected("BACKEND_CONTRACT_UNAVAILABLE", HttpStatus.CONFLICT);
        try { return new Frozen(deployment, run, run.getArtifactSetHash(), objectMapper.readTree(openApi)); }
        catch (IOException exception) { throw rejected("BACKEND_CONTRACT_UNAVAILABLE", HttpStatus.CONFLICT); }
    }

    private void validateOperation(JsonNode openApi, OperationRequest request) {
        String template = allowedPath(openApi.path("paths"), request.path());
        if (template == null || !openApi.path("paths").path(template).has(request.method().toLowerCase())) {
            throw rejected("UNSUPPORTED_OPERATION", HttpStatus.BAD_REQUEST);
        }
        JsonNode schema = openApi.path("paths").path(template).path(request.method().toLowerCase())
                .path("requestBody").path("content").path("application/json").path("schema");
        boolean requiresBody = !schema.isMissingNode();
        if (requiresBody && (request.body() == null || !request.body().isObject())) {
            throw rejected("INVALID_OPERATION_BODY", HttpStatus.BAD_REQUEST);
        }
        if (!requiresBody && request.body() != null && !request.body().isNull()) {
            throw rejected("INVALID_OPERATION_BODY", HttpStatus.BAD_REQUEST);
        }
        if (requiresBody && !bodyMatches(schema, request.body(), openApi)) {
            throw rejected("INVALID_OPERATION_BODY", HttpStatus.BAD_REQUEST);
        }
    }

    private static String allowedPath(JsonNode paths, String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("?") || path.contains("#")
                || path.contains("%") || path.contains("{") || path.contains("}") || path.contains("..")
                || path.contains("//") || path.length() > 512) return null;
        if (paths.has(path) && !path.contains("{")) return path;
        for (var entries = paths.fields(); entries.hasNext();) {
            var entry = entries.next(); String[] template = entry.getKey().split("/", -1); String[] actual = path.split("/", -1);
            if (template.length != actual.length) continue;
            int variables = 0;
            boolean matches = true;
            for (int i = 1; i < template.length; i++) {
                if (template[i].startsWith("{") && template[i].endsWith("}")) {
                    variables++;
                    // Generated CRUD item routes have one final UUID identifier; do not admit arbitrary templates.
                    matches &= i == template.length - 1 && UUID_SEGMENT.matcher(actual[i]).matches();
                }
                else matches &= template[i].equals(actual[i]);
            }
            if (matches && variables == 1) return entry.getKey();
        }
        return null;
    }

    private static boolean bodyMatches(JsonNode schema, JsonNode body, JsonNode root) {
        if (schema.has("$ref")) {
            String reference = schema.path("$ref").asText();
            if (!reference.startsWith("#/components/schemas/")) return false;
            schema = root.path("components").path("schemas").path(reference.substring(reference.lastIndexOf('/') + 1));
        }
        if (body == null || body.isNull()) return schema.path("nullable").asBoolean(false);
        String type = schema.path("type").asText();
        if ("object".equals(type)) {
            if (!body.isObject()) return false;
            for (JsonNode required : schema.path("required")) if (!body.has(required.asText()) || body.path(required.asText()).isNull()) return false;
            JsonNode properties = schema.path("properties");
            var fields = body.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!properties.has(field.getKey()) || !bodyMatches(properties.path(field.getKey()), field.getValue(), root)) return false;
            }
            return true;
        }
        if ("array".equals(type)) {
            if (!body.isArray()) return false;
            for (JsonNode item : body) if (!bodyMatches(schema.path("items"), item, root)) return false;
            return true;
        }
        if ("string".equals(type)) {
            if (!body.isTextual()) return false;
            String format = schema.path("format").asText();
            if ("uuid".equals(format)) return UUID_SEGMENT.matcher(body.asText()).matches();
            if ("date".equals(format)) return body.asText().matches("\\d{4}-\\d{2}-\\d{2}");
            if ("date-time".equals(format)) return body.asText().matches("\\d{4}-\\d{2}-\\d{2}T.*");
            return true;
        }
        if ("integer".equals(type)) return body.isIntegralNumber();
        if ("number".equals(type)) return body.isNumber();
        if ("boolean".equals(type)) return body.isBoolean();
        return false;
    }

    private HttpRequest upstreamRequest(URI uri, OperationRequest request) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(properties.getRequestTimeoutMillis()))
                .header("Accept", "application/json");
        if (request.body() != null && !request.body().isNull()) {
            byte[] serialized = objectMapper.writeValueAsBytes(request.body());
            if (serialized.length > MAX_OPERATION_BODY_BYTES) throw rejected("OPERATION_BODY_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE);
            builder.header("Content-Type", "application/json")
                    .method(request.method(), HttpRequest.BodyPublishers.ofByteArray(serialized));
        }
        else builder.method(request.method(), HttpRequest.BodyPublishers.noBody());
        return builder.build();
    }

    private URI upstreamUri(int port, String path) {
        String host = properties.getUpstreamHost();
        if (host == null || !HOST.matcher(host).matches() || port < FIRST_GENERATED_HTTP_PORT || port > LAST_GENERATED_HTTP_PORT) {
            throw rejected("UPSTREAM_CONFIGURATION_INVALID", HttpStatus.SERVICE_UNAVAILABLE);
        }
        return URI.create("http://" + host + ":" + port + path);
    }
    private UpstreamResponse forwardOnce(URI target, OperationRequest request) {
        var subscriber = new BoundedBodySubscriber(objectMapper, properties.getMaxResponseBytes());
        CompletableFuture<HttpResponse<JsonNode>> operation;
        try { operation = client.sendAsync(upstreamRequest(target, request), ignored -> subscriber); }
        catch (IOException exception) { throw rejected("UPSTREAM_UNAVAILABLE", HttpStatus.BAD_GATEWAY); }
        try {
            var response = operation.get(properties.getRequestTimeoutMillis(), TimeUnit.MILLISECONDS);
            return new UpstreamResponse(response.statusCode(), response.body());
        } catch (TimeoutException exception) {
            subscriber.cancel(); operation.cancel(true);
            throw rejected("UPSTREAM_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT);
        } catch (InterruptedException exception) {
            subscriber.cancel(); operation.cancel(true); Thread.currentThread().interrupt();
            throw rejected("UPSTREAM_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT);
        } catch (ExecutionException exception) {
            subscriber.cancel();
            Throwable cause = exception.getCause();
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof ApiException apiException) throw apiException;
            if (cause instanceof java.net.http.HttpTimeoutException) throw rejected("UPSTREAM_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT);
            throw rejected("UPSTREAM_UNAVAILABLE", HttpStatus.BAD_GATEWAY);
        }
    }

    /** Nonblocking bounded body ownership; cancellation also fences late subscription publication. */
    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<JsonNode> {
        private final ObjectMapper json;
        private final int limit;
        private final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        private final CompletableFuture<JsonNode> body = new CompletableFuture<>();
        private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        private final AtomicReference<java.util.concurrent.Flow.Subscription> subscription = new AtomicReference<>();
        BoundedBodySubscriber(ObjectMapper json, int limit) { this.json = json; this.limit = limit; }
        public java.util.concurrent.CompletionStage<JsonNode> getBody() { return body; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            if (!subscription.compareAndSet(null, value) || cancelled.get()) { value.cancel(); return; }
            value.request(1);
        }
        public void onNext(List<java.nio.ByteBuffer> chunks) {
            if (cancelled.get() || body.isDone()) { cancel(); return; }
            for (var chunk : chunks) {
                if (chunk.remaining() > limit - output.size()) {
                    body.completeExceptionally(rejected("UPSTREAM_RESPONSE_TOO_LARGE", HttpStatus.BAD_GATEWAY));
                    cancelSubscription(); return;
                }
                byte[] bytes = new byte[chunk.remaining()]; chunk.get(bytes); output.writeBytes(bytes);
            }
            if (!cancelled.get() && !body.isDone()) subscription.get().request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); cancelSubscription(); }
        public void onComplete() {
            if (body.isDone()) return;
            try { body.complete(output.size() == 0 ? json.nullNode() : json.readTree(output.toByteArray())); }
            catch (IOException error) { body.completeExceptionally(error); }
        }
        void cancel() { cancelSubscription(); body.cancel(true); }
        private void cancelSubscription() {
            cancelled.set(true);
            var current = subscription.get(); if (current != null) current.cancel();
        }
    }
    private CurrentUser requireAuthenticatedMember(UUID projectId, boolean write) {
        CurrentUser user = users.require(); // Operators have no user identity and may not reach generated data.
        if (write) authorization.requireEditor(projectId); else authorization.requireMember(projectId);
        return user;
    }
    private void requireMember(UUID projectId) { requireAuthenticatedMember(projectId, false); }
    private static UUID projectId(Deployment deployment) { return deployment.getRun().getModel().getProject().getId(); }
    private static boolean isWrite(String method) { return "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method); }
    private void audit(CurrentUser user, UUID projectId, UUID deploymentId, String method, String path, int status) {
        LOG.info("mobile_gateway userId={} projectId={} deploymentId={} method={} route={} status={}", user.userId(), projectId, deploymentId, method, path, status);
    }
    private static ApiException rejected(String code, HttpStatus status) { return new ApiException(status, code, "The mobile gateway request was rejected.", List.of(), null); }
    private record Frozen(Deployment deployment, GenerationRun run, String contractHash, JsonNode openApi) { }
    private record UpstreamResponse(int status, JsonNode body) { }
    private static final class UpstreamFailure extends RuntimeException { UpstreamFailure(Exception cause) { super(cause); } }
}
