package com.diagramador.mobile.agent;

import static com.diagramador.mobile.agent.MobileAgentOperationStore.failure;
import com.diagramador.api.ApiException;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.mobile.MobileGatewayResources.OperationRequest;
import com.diagramador.mobile.MobileGatewayService;
import com.diagramador.mobile.agent.MobileAgentOperationResources.Receipt;
import com.diagramador.mobile.agent.MobileAgentOperationResources.Request;
import com.diagramador.mobile.agent.MobileAgentOperationStore.Stored;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Structured intent only: this boundary does not infer natural-language permission. */
@Service
public class MobileAgentOperationService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MobileAgentOperationService.class);
    private final MobileGatewayService gateway;
    private final MobileAgentOperationStore store;
    private final CurrentUserContext users;
    private final ProjectAuthorizationService authorization;
    private final ObjectMapper json;

    public MobileAgentOperationService(MobileGatewayService gateway, MobileAgentOperationStore store,
            CurrentUserContext users, ProjectAuthorizationService authorization, ObjectMapper json) {
        this.gateway = gateway;
        this.store = store;
        this.users = users;
        this.authorization = authorization;
        this.json = json;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Receipt submit(UUID project, UUID deployment, Request request) {
        users.require();
        try { return submitValidated(project, deployment, request); }
        catch (ApiException rejected) {
            LOG.info("mobile_agent_operation userId={} projectId={} deploymentId={} event=rejected code={}",
                    users.require().userId(), project, deployment, rejected.code());
            throw rejected;
        }
    }

    private Receipt submitValidated(UUID project, UUID deployment, Request request) {
        validate(request);
        var prepared = gateway.prepareAgent(project, deployment,
                new OperationRequest(request.contractHash(), request.method(), request.path(), request.body(), false));
        String canonical = MobileAgentOperationResources.canonical(json, request);
        var claim = store.claim(prepared.userId(), project, deployment, request, canonical);
        if (!claim.dispatch()) return receipt(claim.stored());
        Integer status;
        String result;
        try {
            var response = gateway.dispatchAgent(prepared);
            status = response.status();
            result = response.body() == null ? "null" : response.body().toString();
            var intent = intent(claim.stored());
            var envelope = new Receipt(request.operationId(), request.contractHash(), intent.path("method").asText(),
                    intent.path("path").asText(), intent.path("body"), "completed", null, status, response.body(), null);
            if (result.getBytes(StandardCharsets.UTF_8).length > 1_048_576 || serializedSize(envelope) > 1_048_576) {
                throw failure("UPSTREAM_RESPONSE_TOO_LARGE", HttpStatus.BAD_GATEWAY);
            }
        } catch (RuntimeException failure) {
            String code = failure instanceof ApiException api ? api.code() : "UPSTREAM_UNAVAILABLE";
            return receipt(store.finish(request.operationId(), null, null, code));
        }
        // If persistence fails after an upstream result, the committed dispatching row remains non-retryable.
        return receipt(store.finish(request.operationId(), status, result, null));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Receipt lookup(UUID project, UUID deployment, UUID operation, String hash) {
        UUID user = users.require().userId();
        authorization.requireMember(project);
        var contract = gateway.contract(project, deployment);
        var stored = store.lookup(user, project, deployment, operation);
        var intent = intent(stored);
        if (!"GET".equals(intent.path("method").asText())) authorization.requireEditor(project);
        if (!stored.contractHash().equals(hash) || !contract.contractHash().equals(hash)) {
            throw failure("CONTRACT_MISMATCH", HttpStatus.CONFLICT);
        }
        return receipt(stored);
    }

    private Receipt receipt(Stored stored) {
        LOG.info("mobile_agent_operation userId={} projectId={} deploymentId={} operationId={} state={}",
                stored.userId(), stored.projectId(), stored.deploymentId(), stored.operationId(), stored.state());
        var intent = intent(stored);
        String token = "awaiting_confirmation".equals(stored.state())
                ? MobileAgentOperationResources.token(stored.userId(), stored.projectId(), stored.deploymentId(), stored.operationId(), stored.canonical())
                : null;
        try {
            return new Receipt(stored.operationId(), stored.contractHash(), intent.path("method").asText(), intent.path("path").asText(),
                    intent.path("body"), stored.state(), token, stored.status(), stored.result() == null ? null : json.readTree(stored.result()), stored.error());
        } catch (java.io.IOException invalidStoredResult) {
            throw new IllegalStateException("Invalid persisted operation result", invalidStoredResult);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode intent(Stored stored) {
        try { return json.readTree(stored.canonical()); }
        catch (java.io.IOException invalidStoredIntent) { throw new IllegalStateException("Invalid persisted operation intent", invalidStoredIntent); }
    }

    private int serializedSize(Receipt receipt) {
        try { return json.writeValueAsBytes(receipt).length; }
        catch (java.io.IOException invalidResponse) { throw failure("UPSTREAM_UNAVAILABLE", HttpStatus.BAD_GATEWAY); }
    }

    private static void validate(Request request) {
        if (request == null || request.operationId() == null || request.contractHash() == null
                || !request.contractHash().matches("[a-f0-9]{64}") || request.method() == null
                || !List.of("GET", "POST", "PUT", "DELETE").contains(request.method())
                || request.path() == null || request.path().length() > 512
                || (request.confirmationToken() != null && request.confirmationToken().length() > 64)) {
            throw failure("INVALID_OPERATION", HttpStatus.BAD_REQUEST);
        }
        if ("DELETE".equals(request.method()) && !request.path().matches(".*/[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) {
            throw failure("DELETE_TARGET_REQUIRED", HttpStatus.BAD_REQUEST);
        }
        if (!"DELETE".equals(request.method()) && request.confirmationToken() != null) {
            throw failure("CONFIRMATION_NOT_APPLICABLE", HttpStatus.BAD_REQUEST);
        }
    }
}
