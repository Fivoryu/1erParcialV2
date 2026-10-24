package com.diagramador.collaboration;

import com.diagramador.api.ApiException;
import com.diagramador.assistant.AssistantApplyOp;
import com.diagramador.assistant.AssistantApplyRequest;
import com.diagramador.assistant.AssistantApplyResponse;
import com.diagramador.assistant.AssistantApplyService;
import com.diagramador.assistant.AssistantPlanOperation;
import com.diagramador.assistant.AssistantPlanRequest;
import com.diagramador.assistant.AssistantPlanResponse;
import com.diagramador.assistant.AssistantPlanService;
import com.diagramador.api.ProjectApiService;
import com.diagramador.domain.ChatProposal;
import com.diagramador.domain.DiagramMessage;
import com.diagramador.domain.UserAccount;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.ChatProposalRepository;
import com.diagramador.persistence.UserAccountRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The assistant as one more member of the chat (S6): a member asks for a change, the plan is
 * stored as a PROPOSAL card every member sees with its preview, and an editor accepts or rejects
 * it. Accepting applies the plan through {@link AssistantApplyService}, which revalidates every
 * step against the live diagram and runs it through the normal command path with the accepting
 * user's rights, so the proposal itself carries no authority.
 */
@Service
public class ChatProposalService {

    private static final Logger logger = LoggerFactory.getLogger(ChatProposalService.class);
    private static final int MAX_UTTERANCE_LENGTH = 1000;
    private static final int CARD_TITLE_LENGTH = 300;
    private static final TypeReference<List<AssistantPlanOperation>> OPERATIONS_TYPE = new TypeReference<>() {
    };

    private final ChatService chatService;
    private final AssistantPlanService assistantPlanService;
    private final AssistantApplyService assistantApplyService;
    private final ProjectApiService projectApiService;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final CurrentUserContext currentUserContext;
    private final UserAccountRepository userAccountRepository;
    private final ChatProposalRepository proposalRepository;
    private final ProjectEventBus eventBus;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ChatProposalService(ChatService chatService, AssistantPlanService assistantPlanService,
            AssistantApplyService assistantApplyService, ProjectApiService projectApiService,
            ProjectAuthorizationService projectAuthorizationService, CurrentUserContext currentUserContext,
            UserAccountRepository userAccountRepository, ChatProposalRepository proposalRepository,
            ProjectEventBus eventBus, ObjectMapper objectMapper, TransactionTemplate transactionTemplate) {
        this.chatService = chatService;
        this.assistantPlanService = assistantPlanService;
        this.assistantApplyService = assistantApplyService;
        this.projectApiService = projectApiService;
        this.projectAuthorizationService = projectAuthorizationService;
        this.currentUserContext = currentUserContext;
        this.userAccountRepository = userAccountRepository;
        this.proposalRepository = proposalRepository;
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public record ProposalResource(UUID id, UUID messageId, UUID sessionId, UUID requestedByUserId,
            String requestedByDisplayName, String utterance, List<AssistantPlanOperation> operations,
            long baseRevision, String status, UUID decidedByUserId, String decidedByDisplayName,
            OffsetDateTime decidedAt, String resultSummary, OffsetDateTime createdAt) {
    }

    /**
     * Asks the assistant for a plan and shares it as a PROPOSAL card. The provider call (slow on a
     * small local model) runs outside any database transaction; only the card and its row are
     * written together afterwards.
     */
    public ProposalResource propose(UUID projectId, UUID sessionId, String utterance) {
        String request = utterance == null ? "" : utterance.strip();
        if (request.isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", "Describe the change you want suggested.");
        }
        if (request.length() > MAX_UTTERANCE_LENGTH) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD",
                    "A suggestion request must not exceed " + MAX_UTTERANCE_LENGTH + " characters.");
        }
        chatService.requireAccessibleSession(projectId, sessionId);
        projectAuthorizationService.requireEditor(projectId);

        long baseRevision = projectApiService.getModel(projectId).revision();
        AssistantPlanResponse plan = assistantPlanService.plan(projectId,
                new AssistantPlanRequest(request, baseRevision, null));
        requireUsablePlan(plan);

        String operationsJson = writeOperations(plan.operations());
        UserAccount requester = currentUser();
        ProposalResource resource = transactionTemplate.execute(status -> {
            DiagramMessage card = chatService.savePendingProposalMessage(projectId, sessionId,
                    cardTitle(request));
            ChatProposal saved = proposalRepository.saveAndFlush(
                    ChatProposal.create(card, requester, request, operationsJson, baseRevision));
            chatService.announceAfterCommit(card);
            return resource(saved);
        });
        publishDecision("proposal.created", projectId, resource);
        return resource;
    }

    public List<ProposalResource> list(UUID projectId, UUID sessionId) {
        chatService.requireAccessibleSession(projectId, sessionId);
        return transactionTemplate.execute(status -> proposalRepository
                .findBySession_IdOrderByCreatedAtAscIdAsc(sessionId).stream().map(this::resource).toList());
    }

    /**
     * Applies the plan with the accepting editor's rights against the live revision: the
     * proposal's own revision is informational, because the apply path revalidates every step.
     * The PENDING to APPLYING claim is atomic, so two editors accepting at once apply it once.
     */
    public ProposalResource accept(UUID projectId, UUID sessionId, UUID proposalId) {
        chatService.requireAccessibleSession(projectId, sessionId);
        projectAuthorizationService.requireEditor(projectId);
        List<AssistantPlanOperation> operations = claim(sessionId, proposalId, ChatProposal.APPLYING);

        AssistantApplyResponse applied;
        try {
            long liveRevision = projectApiService.getModel(projectId).revision();
            applied = assistantApplyService.apply(projectId, new AssistantApplyRequest(operations.stream()
                    .map(operation -> new AssistantApplyOp(operation.type(), operation.targetId(),
                            operation.args()))
                    .toList(), liveRevision));
        } catch (RuntimeException failure) {
            // Never leave a claimed proposal stuck in APPLYING: record the failure, then surface it.
            finish(projectId, sessionId, proposalId, ChatProposal.FAILED, "The change could not be applied.");
            throw failure;
        }

        long accepted = applied.steps().stream().filter(step -> "ACCEPTED".equals(step.status())).count();
        long skipped = applied.steps().stream().filter(step -> "SKIPPED".equals(step.status())).count();
        boolean stopped = applied.steps().stream().anyMatch(step -> "REJECTED".equals(step.status()));
        String status = accepted > 0 ? ChatProposal.ACCEPTED : ChatProposal.FAILED;
        return finish(projectId, sessionId, proposalId, status, summarize(accepted, skipped, stopped, applied));
    }

    public ProposalResource reject(UUID projectId, UUID sessionId, UUID proposalId) {
        chatService.requireAccessibleSession(projectId, sessionId);
        projectAuthorizationService.requireEditor(projectId);
        claim(sessionId, proposalId, ChatProposal.REJECTED);
        return finish(projectId, sessionId, proposalId, ChatProposal.REJECTED, "Rejected without changes.");
    }

    /** Atomically takes a PENDING proposal; a lost race or an earlier decision is a 409. */
    private List<AssistantPlanOperation> claim(UUID sessionId, UUID proposalId, String to) {
        return transactionTemplate.execute(status -> {
            ChatProposal proposal = proposalRepository.findById(proposalId)
                    .filter(found -> found.getSession().getId().equals(sessionId))
                    .orElseThrow(ChatProposalService::notFound);
            if (proposalRepository.transition(proposalId, sessionId, ChatProposal.PENDING, to) == 0) {
                throw alreadyDecided();
            }
            return readOperations(proposal.getOperations());
        });
    }

    /** Records the outcome of a proposal this caller claimed, then tells the chat and the project. */
    private ProposalResource finish(UUID projectId, UUID sessionId, UUID proposalId, String status,
            String summary) {
        UserAccount decider = currentUser();
        ProposalResource resource = transactionTemplate.execute(txStatus -> {
            ChatProposal proposal = proposalRepository.findById(proposalId)
                    .orElseThrow(ChatProposalService::notFound);
            proposal.decide(status, decider, summary);
            proposalRepository.saveAndFlush(proposal);
            return resource(proposal);
        });
        String verb = ChatProposal.REJECTED.equals(status) ? "rejected" : "reviewed";
        chatService.postAiAnswer(sessionId, resource.messageId(),
                decider.getDisplayName() + " " + verb + " the suggestion: " + summary);
        publishDecision("proposal.decided", projectId, resource);
        return resource;
    }

    private static void requireUsablePlan(AssistantPlanResponse plan) {
        if (plan.diagnostics().stream().anyMatch(diagnostic -> "PROVIDER_DISABLED".equals(diagnostic.code()))) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_DISABLED",
                    "The AI assistant is not enabled on this deployment.");
        }
        if (!"PLAN".equals(plan.intent()) || plan.operations().isEmpty()) {
            String reason = firstNonBlank(plan.question(), plan.explanation(),
                    plan.diagnostics().isEmpty() ? null : plan.diagnostics().get(0).message());
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "NO_PROPOSAL",
                    reason == null ? "The assistant had no change to suggest for that request." : reason);
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static String summarize(long accepted, long skipped, boolean stopped,
            AssistantApplyResponse applied) {
        int total = applied.steps().size();
        StringBuilder text = new StringBuilder();
        text.append(accepted).append(" of ").append(total).append(accepted == 1 ? " change" : " changes")
                .append(" applied");
        if (skipped > 0) {
            text.append(", ").append(skipped).append(" skipped");
        }
        if (stopped) {
            String reason = applied.steps().stream()
                    .filter(step -> "REJECTED".equals(step.status()) && !step.diagnostics().isEmpty())
                    .map(step -> step.diagnostics().get(0).message())
                    .findFirst().orElse("a step was rejected");
            text.append("; stopped: ").append(reason);
        }
        return text.append('.').toString();
    }

    private static String cardTitle(String utterance) {
        String line = utterance.replaceAll("\\s+", " ");
        String clipped = line.length() > CARD_TITLE_LENGTH ? line.substring(0, CARD_TITLE_LENGTH) + "…" : line;
        return "Suggested change: " + clipped;
    }

    private ProposalResource resource(ChatProposal proposal) {
        UserAccount requester = proposal.getRequestedBy();
        UserAccount decider = proposal.getDecidedBy();
        return new ProposalResource(proposal.getId(), proposal.getMessage().getId(),
                proposal.getSession().getId(), requester.getId(), requester.getDisplayName(),
                proposal.getUtterance(), readOperations(proposal.getOperations()), proposal.getBaseRevision(),
                proposal.getStatus(), decider == null ? null : decider.getId(),
                decider == null ? null : decider.getDisplayName(), proposal.getDecidedAt(),
                proposal.getResultSummary(), proposal.getCreatedAt());
    }

    private void publishDecision(String type, UUID projectId, ProposalResource resource) {
        try {
            eventBus.publish(ProjectEvent.of(type, projectId,
                    Map.of("proposal", resource, "sessionId", resource.sessionId())));
        } catch (RuntimeException exception) {
            logger.warn("Failed to publish {} for proposal {}", type, resource.id(), exception);
        }
    }

    private String writeOperations(List<AssistantPlanOperation> operations) {
        try {
            return objectMapper.writeValueAsString(operations);
        } catch (JsonProcessingException exception) {
            throw error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "The suggested plan could not be stored.");
        }
    }

    private List<AssistantPlanOperation> readOperations(String json) {
        try {
            return objectMapper.readValue(json, OPERATIONS_TYPE);
        } catch (JsonProcessingException exception) {
            throw error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "The stored plan could not be read.");
        }
    }

    private UserAccount currentUser() {
        UUID userId = currentUserContext.require().userId();
        return userAccountRepository.findById(userId)
                .orElseThrow(() -> error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                        "The authenticated user account was not found."));
    }

    private static ApiException notFound() {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "The suggestion was not found for this session.");
    }

    private static ApiException alreadyDecided() {
        return error(HttpStatus.CONFLICT, "PROPOSAL_DECIDED", "This suggestion was already decided.");
    }

    private static ApiException error(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message, List.of(), null);
    }
}
