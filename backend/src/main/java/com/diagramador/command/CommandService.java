package com.diagramador.command;

import com.diagramador.domain.CascadeGroup;
import com.diagramador.domain.ModelCommand;
import com.diagramador.domain.ModelVersion;
import com.diagramador.domain.Tombstone;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.persistence.CascadeGroupRepository;
import com.diagramador.persistence.ModelCommandRepository;
import com.diagramador.persistence.ModelVersionRepository;
import com.diagramador.persistence.TombstoneRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Applies canonical operation envelopes without exposing persistence exceptions to callers. */
@Service
public class CommandService {

    private final ProjectRepository projectRepository;
    private final UmlModelRepository modelRepository;
    private final com.diagramador.persistence.UserAccountRepository userAccountRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ModelCommandRepository commandRepository;
    private final CascadeGroupRepository cascadeGroupRepository;
    private final TombstoneRepository tombstoneRepository;
    private final ModelVersionRepository versionRepository;
    private final ModelStateHasher stateHasher;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final Map<String, CommandHandler> handlers;

    public CommandService(ProjectRepository projectRepository, UmlModelRepository modelRepository,
            com.diagramador.persistence.UserAccountRepository userAccountRepository,
            ProjectMemberRepository projectMemberRepository, ModelCommandRepository commandRepository,
            CascadeGroupRepository cascadeGroupRepository, TombstoneRepository tombstoneRepository,
            ModelVersionRepository versionRepository, ModelStateHasher stateHasher, ObjectMapper objectMapper,
            EntityManager entityManager, TransactionTemplate transactionTemplate, List<CommandHandler> handlers) {
        this.projectRepository = projectRepository;
        this.modelRepository = modelRepository;
        this.userAccountRepository = userAccountRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.commandRepository = commandRepository;
        this.cascadeGroupRepository = cascadeGroupRepository;
        this.tombstoneRepository = tombstoneRepository;
        this.versionRepository = versionRepository;
        this.stateHasher = stateHasher;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        this.transactionTemplate = transactionTemplate;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(CommandHandler::type, Function.identity()));
    }

    public CommandResult execute(OperationEnvelope envelope) {
        Diagnostic shapeError = validateShape(envelope);
        if (shapeError != null) {
            return rejected(envelope, shapeError);
        }
        try {
            return transactionTemplate.execute(status -> executeInTransaction(envelope));
        } catch (RevisionConflictException exception) {
            return rejected(envelope, new Diagnostic(ErrorCode.REVISION_MISMATCH, exception.getMessage()));
        } catch (CommandValidationException exception) {
            return rejected(envelope, new Diagnostic(exception.code(), exception.getMessage()));
        } catch (RuntimeException exception) {
            return rejected(envelope, new Diagnostic(ErrorCode.MODEL_INVALID,
                    "Command could not be applied: " + exception.getMessage()));
        }
    }

    public CommandResult submit(OperationEnvelope envelope) {
        return execute(envelope);
    }

    public CommandResult apply(OperationEnvelope envelope) {
        return execute(envelope);
    }

    private CommandResult executeInTransaction(OperationEnvelope envelope) {
        CommandHandler handler = handlers.get(envelope.type());
        if (handler == null) {
            return rejected(envelope, new Diagnostic(ErrorCode.UNKNOWN_OPERATION,
                    "No command handler is registered for type " + envelope.type()));
        }
        boolean createsProjectAndModel = handler.createsProjectAndModel();

        Project project = projectRepository.findById(envelope.projectId()).orElse(null);
        UmlModel model = modelRepository.findById(envelope.modelId()).orElse(null);
        if (createsProjectAndModel) {
            if ((project == null) != (model == null)) {
                return rejected(envelope, new Diagnostic(ErrorCode.PRECONDITION_FAILED,
                        "Project " + envelope.projectId() + " and model " + envelope.modelId()
                                + " must either both exist or both be absent"));
            }
        } else if (project == null || model == null) {
            return rejected(envelope, new Diagnostic(ErrorCode.PRECONDITION_FAILED,
                    "Project " + envelope.projectId() + " or model " + envelope.modelId() + " does not exist"));
        }
        if (project != null && model != null) {
            UUID actualProjectId = model.getProject().getId();
            if (!actualProjectId.equals(project.getId())) {
                return rejected(envelope, new Diagnostic(ErrorCode.PRECONDITION_FAILED,
                        "Model " + envelope.modelId() + " belongs to project " + actualProjectId
                                + ", not project " + envelope.projectId()));
            }
        }

        UserAccount author = userAccountRepository.findById(envelope.author().userId()).orElse(null);
        if (author == null) {
            return rejected(envelope, new Diagnostic(ErrorCode.UNAUTHORIZED,
                    "Author " + envelope.author().userId() + " is unknown"));
        }
        ProjectMember member = null;
        if (!createsProjectAndModel) {
            member = projectMemberRepository
                    .findByProjectIdAndUserId(project.getId(), author.getId()).orElse(null);
            if (member == null || member.getStatus().equals("REMOVED") || !isEditorOrOwner(member.getRole())) {
                return rejected(envelope, new Diagnostic(ErrorCode.FORBIDDEN,
                        "Author " + author.getId() + " is not an OWNER or EDITOR of project " + project.getId()));
            }
        }

        if (model != null) {
            Optional<ModelCommand> original = commandRepository
                    .findByModelIdAndIdempotencyKey(model.getId(), envelope.idempotencyKey());
            if (original.isPresent()) {
                ModelCommand command = original.get();
                return new CommandResult(command.getCommandId(), CommandStatus.ACCEPTED, command.getResultRevision(),
                        List.of(), command.getAuthorizationClassification());
            }
        }

        long expectedRevision = model == null ? 0L : model.getCurrentRevision();
        if (envelope.baseRevision() != expectedRevision) {
            return rejected(envelope, new Diagnostic(ErrorCode.REVISION_MISMATCH,
                    "Base revision " + envelope.baseRevision() + " does not match current revision "
                            + expectedRevision));
        }

        Diagnostic preconditionError = evaluatePreconditions(envelope, project, member);
        if (preconditionError != null) {
            return rejected(envelope, preconditionError);
        }

        JsonNode canonicalEnvelope;
        try {
            canonicalEnvelope = objectMapper.valueToTree(envelope);
        } catch (IllegalArgumentException exception) {
            return rejected(envelope, new Diagnostic(ErrorCode.INVALID_PAYLOAD,
                    "Envelope cannot be represented as canonical JSON"));
        }

        CommandHandler.Outcome outcome = handler.apply(
                new CommandHandler.Context(envelope, project, model, entityManager));
        validateCascadePrecondition(envelope, outcome);
        entityManager.flush();

        if (createsProjectAndModel && model == null) {
            project = projectRepository.findById(envelope.projectId()).orElse(null);
            model = modelRepository.findById(envelope.modelId()).orElse(null);
            if (project == null || model == null) {
                throw new CommandValidationException(ErrorCode.MODEL_INVALID,
                        "Creating handler did not create project " + envelope.projectId()
                                + " and model " + envelope.modelId());
            }
        }

        long nextRevision = expectedRevision + 1;
        ModelCommand command = ModelCommand.create(envelope.operationId(), model, author, expectedRevision, nextRevision,
                envelope.type(), firstTargetType(envelope), firstTargetId(envelope), canonicalEnvelope,
                envelope.origin(), AuthorizationClassification.AUTHORIZED_ONLINE,
                envelope.causalParentOperationId(), envelope.idempotencyKey());
        commandRepository.save(command);
        entityManager.flush();

        if (!outcome.deletedTargets().isEmpty()) {
            CascadeGroup cascade = cascadeGroupRepository.save(
                    CascadeGroup.create(model, command, outcome.deletedTargets().size()));
            for (CommandHandler.DeletedTarget deleted : outcome.deletedTargets()) {
                tombstoneRepository.save(Tombstone.create(model, deleted.targetType(), deleted.targetId(), cascade,
                        command));
            }
            entityManager.flush();
        }

        int updated = modelRepository.bumpRevisionIfCurrent(model.getId(), expectedRevision, nextRevision);
        if (updated != 1) {
            throw new RevisionConflictException("Revision changed while applying operation " + envelope.operationId());
        }
        model.setCurrentRevision(nextRevision);

        String stateHash = stateHasher.hash(model);
        versionRepository.save(ModelVersion.create(model, nextRevision, expectedRevision, stateHash,
                ValidationState.VALID, author));
        return new CommandResult(envelope.operationId(), CommandStatus.ACCEPTED, nextRevision, List.of(),
                AuthorizationClassification.AUTHORIZED_ONLINE);
    }

    private Diagnostic evaluatePreconditions(OperationEnvelope envelope, Project project, ProjectMember member) {
        for (Precondition precondition : envelope.preconditions()) {
            if (precondition == null || precondition.kind() == null) {
                return new Diagnostic(ErrorCode.PRECONDITION_FAILED, "Unknown precondition kind null");
            }
            switch (precondition.kind()) {
                case "model_valid" -> {
                    boolean expected = booleanValue(precondition.expect());
                    boolean actual = versionRepository.findTopByModelIdOrderByRevisionDesc(envelope.modelId())
                            .map(version -> version.getValidationState() == ValidationState.VALID)
                            .orElse(true);
                    if (actual != expected) {
                        return new Diagnostic(ErrorCode.PRECONDITION_FAILED,
                                "Precondition model_valid expected " + expected + " but was " + actual);
                    }
                }
                case "cascade_count" -> {
                    // The actual count is known only after the deletion handler expands the cascade.
                }
                case "permission" -> {
                    ProjectMember current = project == null ? null : projectMemberRepository
                            .findByProjectIdAndUserId(project.getId(), envelope.author().userId()).orElse(null);
                    if (current == null || !isEditorOrOwner(current.getRole())
                            || !permissionMatches(current.getRole(), precondition.expect())) {
                        return new Diagnostic(ErrorCode.PRECONDITION_FAILED,
                                "Precondition permission failed for " + String.valueOf(precondition.expect()));
                    }
                }
                default -> {
                    return new Diagnostic(ErrorCode.PRECONDITION_FAILED,
                            "Unknown precondition kind " + precondition.kind());
                }
            }
        }
        return null;
    }

    private void validateCascadePrecondition(OperationEnvelope envelope, CommandHandler.Outcome outcome) {
        int actualCount = outcome.deletedTargets().size();
        Optional<Precondition> declared = envelope.preconditions().stream()
                .filter(precondition -> precondition != null && "cascade_count".equals(precondition.kind()))
                .findFirst();
        if (declared.isPresent()) {
            Object expect = declared.get().expect();
            Integer expectedCount = cascadeCount(expect);
            if (expectedCount == null) {
                throw new CommandValidationException(ErrorCode.PRECONDITION_FAILED,
                        "Precondition cascade_count must be a non-negative integer");
            }
            if (expectedCount != actualCount) {
                throw new CommandValidationException(ErrorCode.PRECONDITION_FAILED,
                        "Precondition cascade_count expected " + expectedCount + " but actual cascade count was "
                                + actualCount);
            }
        } else if (actualCount > 1) {
            throw new CommandValidationException(ErrorCode.PRECONDITION_FAILED,
                    "Deletion cascade affects " + actualCount
                            + " elements and requires a cascade_count confirmation");
        }
    }

    private static Integer cascadeCount(Object value) {
        if (value instanceof Number number && number.longValue() >= 0 && number.longValue() <= Integer.MAX_VALUE) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                int parsed = Integer.parseInt(string);
                return parsed >= 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Diagnostic validateShape(OperationEnvelope envelope) {
        if (envelope == null) {
            return new Diagnostic(ErrorCode.INVALID_PAYLOAD, "Envelope is required");
        }
        if (envelope.operationId() == null) {
            return new Diagnostic(ErrorCode.INVALID_PAYLOAD, "operationId is required");
        }
        if (blank(envelope.type())) {
            return new Diagnostic(ErrorCode.INVALID_PAYLOAD, "type is required");
        }
        if (envelope.projectId() == null) {
            return new Diagnostic(ErrorCode.INVALID_PAYLOAD, "projectId is required");
        }
        if (envelope.modelId() == null) {
            return new Diagnostic(ErrorCode.INVALID_PAYLOAD, "modelId is required");
        }
        if (envelope.baseRevision() == null) {
            return new Diagnostic(ErrorCode.INVALID_PAYLOAD, "baseRevision is required");
        }
        if (blank(envelope.idempotencyKey())) {
            return new Diagnostic(ErrorCode.INVALID_PAYLOAD, "idempotencyKey is required");
        }
        if (envelope.author() == null || envelope.author().userId() == null) {
            return new Diagnostic(ErrorCode.INVALID_PAYLOAD, "author.userId is required");
        }
        if (envelope.origin() == null) {
            return new Diagnostic(ErrorCode.INVALID_PAYLOAD, "origin is required");
        }
        return null;
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean booleanValue
                ? booleanValue
                : Boolean.parseBoolean(String.valueOf(value));
    }

    private static boolean permissionMatches(ProjectMemberRole role, Object expected) {
        String required = String.valueOf(expected).toUpperCase(Locale.ROOT);
        return switch (required) {
            case "OWNER" -> role == ProjectMemberRole.OWNER;
            case "EDITOR" -> role == ProjectMemberRole.OWNER || role == ProjectMemberRole.EDITOR;
            default -> false;
        };
    }

    private static boolean isEditorOrOwner(ProjectMemberRole role) {
        return role == ProjectMemberRole.OWNER || role == ProjectMemberRole.EDITOR;
    }

    private static TargetType firstTargetType(OperationEnvelope envelope) {
        if (envelope.targets().isEmpty() || envelope.targets().get(0) == null
                || envelope.targets().get(0).scope() == null) {
            return null;
        }
        try {
            return TargetType.valueOf(envelope.targets().get(0).scope().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static UUID firstTargetId(OperationEnvelope envelope) {
        return envelope.targets().isEmpty() || envelope.targets().get(0) == null
                ? null
                : envelope.targets().get(0).id();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static CommandResult rejected(OperationEnvelope envelope, Diagnostic diagnostic) {
        return new CommandResult(envelope == null ? null : envelope.operationId(), CommandStatus.REJECTED, null,
                List.of(diagnostic), null);
    }

    private static final class RevisionConflictException extends RuntimeException {

        private RevisionConflictException(String message) {
            super(message);
        }
    }
}
