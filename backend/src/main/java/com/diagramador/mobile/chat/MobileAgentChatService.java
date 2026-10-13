package com.diagramador.mobile.chat;

import static com.diagramador.mobile.chat.MobileAgentChatResources.*;
import com.diagramador.api.ApiException;
import com.diagramador.domain.Deployment;
import com.diagramador.generation.GenerationArtifactBundleBuilder;
import com.diagramador.generation.GenerationArtifactService;
import com.diagramador.generation.InputSnapshotFactory;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.DeploymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Private history only: stored text and metadata never dispatch generated operations. */
@Service
public class MobileAgentChatService {
    private final AgentConversationRepository conversations;
    private final AgentMessageRepository messages;
    private final CurrentUserContext users;
    private final ProjectAuthorizationService authorization;
    private final DeploymentRepository deployments;
    private final GenerationArtifactService artifacts;
    private final InputSnapshotFactory snapshots;
    private final ObjectMapper mapper;
    private final EntityManager entityManager;
    public MobileAgentChatService(AgentConversationRepository conversations, AgentMessageRepository messages,
            CurrentUserContext users, ProjectAuthorizationService authorization, DeploymentRepository deployments,
            GenerationArtifactService artifacts, InputSnapshotFactory snapshots, ObjectMapper mapper, EntityManager entityManager) {
        this.conversations = conversations; this.messages = messages; this.users = users; this.authorization = authorization;
        this.deployments = deployments; this.artifacts = artifacts; this.snapshots = snapshots;
        this.mapper = mapper; this.entityManager = entityManager;
    }
    @Transactional
    public ConversationResource create(UUID project, UUID deployment, ConversationRequest request) {
        UUID account = scope(project, deployment).account();
        // Serializes absent-row creation too. Hash collisions only cause harmless extra serialization.
        entityManager.createNativeQuery("select 1 from pg_advisory_xact_lock(?1)")
                .setParameter(1, request.id().getMostSignificantBits() ^ request.id().getLeastSignificantBits()).getSingleResult();
        var existing = conversations.findById(request.id());
        if (existing.isPresent()) {
            var conversation = owned(existing.get(), account, project, deployment);
            if (!conversation.title.equals(request.title()) || !conversation.contractHash.equals(request.contractHash())) conflict();
            return resource(conversation);
        }
        Deployment target = scope(project, deployment).deployment();
        requireFrozen(target, request.contractHash());
        var created = conversations.saveAndFlush(new AgentConversation(request.id(), account, project,
                deployment, request.contractHash(), request.title()));
        entityManager.refresh(created); // Return the same canonical resource on first response and retry.
        return resource(created);
    }
    @Transactional(readOnly = true)
    public PageResource<ConversationResource> list(UUID project, UUID deployment, UUID cursor, int limit) {
        UUID account = scope(project, deployment).account();
        limit = bounded(limit);
        var rows = conversations.history(account, project, deployment, cursor, PageRequest.of(0, limit + 1));
        var items = rows.stream().limit(limit).map(MobileAgentChatService::resource).toList();
        return new PageResource<>(items, rows.size() > limit ? items.get(items.size()-1).id().toString() : null);
    }
    @Transactional(readOnly = true)
    public PageResource<MessageResource> history(UUID project, UUID deployment, UUID id, long after, int limit) {
        UUID account = scope(project, deployment).account();
        owned(conversations.findById(id).orElseThrow(MobileAgentChatService::notFound), account, project, deployment);
        if (after < 0) throw invalid();
        limit = bounded(limit);
        var rows = messages.findByConversationIdAndSequenceGreaterThanOrderBySequenceAsc(id, after, PageRequest.of(0, limit + 1));
        var items = rows.stream().limit(limit).map(MobileAgentChatService::resource).toList();
        return new PageResource<>(items, rows.size() > limit ? Long.toString(items.get(items.size()-1).sequence()) : null);
    }
    @Transactional
    public MessageResource append(UUID project, UUID deployment, UUID id, MessageRequest request) {
        UUID account = scope(project, deployment).account();
        var conversation = owned(conversations.lockById(id).orElseThrow(MobileAgentChatService::notFound), account, project, deployment);
        if (request.metadata() != null && (!request.metadata().isObject() || request.metadata().toString().length() > 4096)) throw invalid();
        var existing = messages.findById(request.id());
        if (existing.isPresent()) {
            var message = existing.get();
            if (!message.conversationId.equals(id) || !message.role.equals(request.role()) || !message.content.equals(request.content())
                    || !Objects.equals(message.clientCreatedAt, request.createdAt() == null ? null : request.createdAt().toString()) || !Objects.equals(message.metadata, request.metadata())) conflict();
            return resource(message);
        }
        if (conversation.nextSequence == Long.MAX_VALUE) conflict();
        var message = messages.saveAndFlush(new AgentMessage(conversation, request));
        entityManager.refresh(message); // PostgreSQL canonicalizes display timestamps independently of identity.
        return resource(message);
    }
    private Scope scope(UUID project, UUID deployment) {
        UUID account = users.require().userId();
        authorization.requireMember(project);
        var target = deployments.findById(deployment).orElseThrow(MobileAgentChatService::notFound);
        if (!project.equals(target.getRun().getModel().getProject().getId())) throw notFound();
        return new Scope(account, target);
    }
    private void requireFrozen(Deployment deployment, String hash) {
        var run = deployment.getRun();
        if (!Objects.equals(hash, run.getArtifactSetHash())) throw new ApiException(HttpStatus.CONFLICT,
                "CONTRACT_MISMATCH", "The frozen contract does not match.", List.of(), null);
        var bundle = artifacts.downloadComplete(run.getId(), snapshots.create(run.getVersion()));
        String openApi = bundle.map(value -> value.files().get(GenerationArtifactBundleBuilder.OPENAPI_PATH)).orElse(null);
        try {
            var document = openApi == null ? null : mapper.readTree(openApi);
            if (document == null || !document.isObject() || !document.path("paths").isObject()) throw contractUnavailable();
        } catch (java.io.IOException exception) { throw contractUnavailable(); }
    }
    private static AgentConversation owned(AgentConversation c, UUID user, UUID project, UUID deployment) {
        if (!c.userId.equals(user) || !c.projectId.equals(project) || !c.deploymentId.equals(deployment)) throw notFound();
        return c;
    }
    private static int bounded(int limit) { if (limit < 1 || limit > 100) throw invalid(); return limit; }
    private static ApiException contractUnavailable() { return new ApiException(HttpStatus.CONFLICT, "BACKEND_CONTRACT_UNAVAILABLE", "The frozen contract is unavailable.", List.of(), null); }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHAT_REQUEST", "Chat request is invalid.", List.of(), null); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "CHAT_NOT_FOUND", "Chat is unavailable.", List.of(), null); }
    private static void conflict() { throw new ApiException(HttpStatus.CONFLICT, "CHAT_ID_CONFLICT", "The identifier already has a different payload.", List.of(), null); }
    private static ConversationResource resource(AgentConversation c) { return new ConversationResource(c.id, c.title, c.contractHash, c.createdAt, c.updatedAt); }
    private static MessageResource resource(AgentMessage m) { return new MessageResource(m.id, m.role, m.content, m.createdAt, m.sequence, m.metadata); }
    private record Scope(UUID account, Deployment deployment) {}
}
