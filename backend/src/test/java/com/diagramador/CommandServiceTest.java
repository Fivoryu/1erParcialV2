package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.command.AuthorizationClassification;
import com.diagramador.command.CommandHandler;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.ErrorCode;
import com.diagramador.command.ModelStateHasher;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.command.Precondition;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.ModelCommand;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.ModelCommandRepository;
import com.diagramador.persistence.ModelVersionRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UserAccountRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class CommandServiceTest extends AbstractPostgresIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final CommandService commandService;
    private final UserAccountRepository userAccountRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UmlModelRepository modelRepository;
    private final ModelCommandRepository commandRepository;
    private final ModelVersionRepository versionRepository;
    private final ClassifierRepository classifierRepository;
    private final ModelStateHasher stateHasher;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    CommandServiceTest(CommandService commandService, UserAccountRepository userAccountRepository,
            ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository,
            UmlModelRepository modelRepository, ModelCommandRepository commandRepository,
            ModelVersionRepository versionRepository, ClassifierRepository classifierRepository,
            ModelStateHasher stateHasher, TransactionTemplate transactionTemplate) {
        this.commandService = commandService;
        this.userAccountRepository = userAccountRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.modelRepository = modelRepository;
        this.commandRepository = commandRepository;
        this.versionRepository = versionRepository;
        this.classifierRepository = classifierRepository;
        this.stateHasher = stateHasher;
        this.transactionTemplate = transactionTemplate;
    }

    @Test
    void missingEnvelopeFieldsAreInvalidAndKeepPresentOperationId() {
        Fixture fixture = fixture(ProjectMemberRole.OWNER);
        UUID operationId = UUID.randomUUID();
        OperationEnvelope missingType = envelope(fixture, operationId, null, 0L, "missing-type");
        OperationEnvelope missingProject = new OperationEnvelope(operationId, "test.noop", fixture.author(), null,
                fixture.model().getId(), 0L, List.of(), List.of(), Map.of(), CommandOrigin.MANUAL, null, "missing-project");
        OperationEnvelope missingRevision = envelope(fixture, operationId, "test.noop", null, "missing-revision");
        OperationEnvelope missingModel = new OperationEnvelope(operationId, "test.noop", fixture.author(),
                fixture.project().getId(), null, 0L, List.of(), List.of(), Map.of(), CommandOrigin.MANUAL, null,
                "missing-model");
        OperationEnvelope missingIdempotency = envelope(fixture, operationId, "test.noop", 0L, null);

        assertError(missingType, ErrorCode.INVALID_PAYLOAD, operationId);
        assertError(missingProject, ErrorCode.INVALID_PAYLOAD, operationId);
        assertError(missingRevision, ErrorCode.INVALID_PAYLOAD, operationId);
        assertError(missingModel, ErrorCode.INVALID_PAYLOAD, operationId);
        assertError(missingIdempotency, ErrorCode.INVALID_PAYLOAD, operationId);
        CommandResult missingOperationId = commandService.execute(
                envelope(fixture, null, "test.noop", 0L, "missing-operation-id"));
        assertThat(missingOperationId.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(missingOperationId.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(ErrorCode.INVALID_PAYLOAD);
        assertThat(missingOperationId.operationId()).isNull();
    }

    @Test
    void unknownCommandTypeNamesTheType() {
        Fixture fixture = fixture(ProjectMemberRole.OWNER);
        UUID operationId = UUID.randomUUID();

        CommandResult result = commandService.execute(envelope(fixture, operationId, "unknown.command", 0L,
                "unknown-type"));

        assertThat(result.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(result.operationId()).isEqualTo(operationId);
        assertThat(result.diagnostics()).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.code()).isEqualTo(ErrorCode.UNKNOWN_OPERATION));
        assertThat(result.diagnostics().get(0).message()).contains("unknown.command");
    }

    @Test
    void fallenBehindBaseRevisionIsRejected() {
        Fixture fixture = fixture(ProjectMemberRole.OWNER);
        CommandResult first = commandService.execute(envelope(fixture, UUID.randomUUID(), "test.noop", 0L, "rev-1"));
        CommandResult second = commandService.execute(envelope(fixture, UUID.randomUUID(), "test.noop", 0L, "rev-2"));

        assertThat(first.status()).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(second.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(second.operationId()).isNotNull();
        assertThat(second.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(ErrorCode.REVISION_MISMATCH);
    }

    @Test
    void viewerIsForbidden() {
        Fixture fixture = fixture(ProjectMemberRole.VIEWER);
        CommandResult result = commandService.execute(envelope(fixture, UUID.randomUUID(), "test.noop", 0L, "viewer"));

        assertError(result, ErrorCode.FORBIDDEN, result.operationId());
    }

    @Test
    void nonMemberIsForbidden() {
        Fixture fixture = fixtureWithoutAuthorMembership();
        CommandResult result = commandService.execute(envelope(fixture, UUID.randomUUID(), "test.noop", 0L, "non-member"));

        assertError(result, ErrorCode.FORBIDDEN, result.operationId());
    }

    @Test
    void unknownAuthorIsUnauthorized() {
        Fixture fixture = fixture(ProjectMemberRole.OWNER);
        AuthorId author = new AuthorId(UUID.randomUUID());
        OperationEnvelope envelope = new OperationEnvelope(UUID.randomUUID(), "test.noop",
                new com.diagramador.command.Author(author.id(), "unknown"), fixture.project().getId(),
                fixture.model().getId(), 0L, List.of(), List.of(), Map.of(), CommandOrigin.MANUAL, null, "unknown-user");

        CommandResult result = commandService.execute(envelope);

        assertError(result, ErrorCode.UNAUTHORIZED, envelope.operationId());
    }

    @Test
    void unknownPreconditionKindIsRejectedAndNamed() {
        Fixture fixture = fixture(ProjectMemberRole.OWNER);
        OperationEnvelope envelope = new OperationEnvelope(UUID.randomUUID(), "test.noop", fixture.author(),
                fixture.project().getId(), fixture.model().getId(), 0L, List.of(),
                List.of(new Precondition("future_predicate", true, null)), Map.of(), CommandOrigin.MANUAL, null,
                "unknown-precondition");

        CommandResult result = commandService.execute(envelope);

        assertError(result, ErrorCode.PRECONDITION_FAILED, envelope.operationId());
        assertThat(result.diagnostics().get(0).message()).contains("future_predicate");
    }

    @Test
    void happyPathPersistsCommandAndVersion() {
        Fixture fixture = fixture(ProjectMemberRole.OWNER);
        UUID operationId = UUID.randomUUID();
        OperationEnvelope envelope = envelope(fixture, operationId, "test.noop", 0L, "happy");

        CommandResult result = commandService.execute(envelope);

        assertThat(result.status()).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(result.operationId()).isEqualTo(operationId);
        assertThat(result.resultRevision()).isEqualTo(1L);
        assertThat(result.authorizationClassification()).isEqualTo(AuthorizationClassification.AUTHORIZED_ONLINE);
        assertThat(modelRepository.findById(fixture.model().getId()).orElseThrow().getCurrentRevision()).isEqualTo(1L);
        assertThat(versionRepository.countByModelId(fixture.model().getId())).isEqualTo(1L);
        assertThat(versionRepository.findTopByModelIdOrderByRevisionDesc(fixture.model().getId()).orElseThrow()
                .getStateHash()).matches("[0-9a-f]{64}");
        assertThat(commandRepository.findById(operationId).orElseThrow().getCommandId()).isEqualTo(operationId);
    }

    @Test
    void idempotentReplayReturnsOriginalResultWithoutChangingCounts() {
        Fixture fixture = fixture(ProjectMemberRole.OWNER);
        OperationEnvelope envelope = envelope(fixture, UUID.randomUUID(), "test.noop", 0L, "replay");
        CommandResult first = commandService.execute(envelope);
        long commands = commandRepository.countByModelId(fixture.model().getId());
        long versions = versionRepository.countByModelId(fixture.model().getId());
        long revision = modelRepository.findById(fixture.model().getId()).orElseThrow().getCurrentRevision();

        CommandResult second = commandService.execute(envelope);

        assertThat(second.status()).as("second returns the original status").isEqualTo(first.status());
        assertThat(second.resultRevision()).as("second returns the original resultRevision")
                .isEqualTo(first.resultRevision());
        assertThat(commandRepository.countByModelId(fixture.model().getId())).as("no second model_command row")
                .isEqualTo(commands);
        assertThat(versionRepository.countByModelId(fixture.model().getId())).as("no second model_version row")
                .isEqualTo(versions);
        assertThat(modelRepository.findById(fixture.model().getId()).orElseThrow().getCurrentRevision())
                .as("no second revision bump").isEqualTo(revision);
    }

    @Test
    void sequentialEnvelopesWithSameBaseRevisionRejectTheSecond() {
        Fixture fixture = fixture(ProjectMemberRole.OWNER);
        OperationEnvelope firstEnvelope = envelope(fixture, UUID.randomUUID(), "test.noop", 0L, "ordered-1");
        OperationEnvelope secondEnvelope = envelope(fixture, UUID.randomUUID(), "test.noop", 0L, "ordered-2");

        CommandResult first = commandService.execute(firstEnvelope);
        CommandResult second = commandService.execute(secondEnvelope);

        assertThat(first.status()).as("first envelope is accepted").isEqualTo(CommandStatus.ACCEPTED);
        assertThat(second.status()).as("second envelope sharing the stale base is rejected")
                .isEqualTo(CommandStatus.REJECTED);
        assertThat(second.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(ErrorCode.REVISION_MISMATCH);
    }

    @Test
    void modelStateHasherIsDeterministicAndDetectsFieldChanges() {
        Fixture fixture = fixture(ProjectMemberRole.OWNER);
        String[] hashes = transactionTemplate.execute(status -> {
            Classifier classifier = classifierRepository.save(
                    Classifier.create(fixture.model(), null, ClassifierKind.CLASS, "Stable", false));
            String first = stateHasher.hash(fixture.model());
            String second = stateHasher.hash(fixture.model());
            classifier.setName("Changed");
            String changed = stateHasher.hash(fixture.model());
            return new String[] { first, second, changed };
        });

        assertThat(hashes[0]).isEqualTo(hashes[1]);
        assertThat(hashes[2]).isNotEqualTo(hashes[0]);
        assertThat(hashes[0]).matches("[0-9a-f]{64}");
    }

    private void assertError(OperationEnvelope envelope, ErrorCode code, UUID operationId) {
        assertError(commandService.execute(envelope), code, operationId);
    }

    private void assertError(CommandResult result, ErrorCode code, UUID operationId) {
        assertThat(result.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(result.operationId()).isEqualTo(operationId);
        assertThat(result.diagnostics()).extracting(diagnostic -> diagnostic.code()).containsExactly(code);
    }

    private OperationEnvelope envelope(Fixture fixture, UUID operationId, String type, Long baseRevision,
            String idempotencyKey) {
        return new OperationEnvelope(operationId, type, fixture.author(), fixture.project().getId(),
                fixture.model().getId(), baseRevision, List.of(), List.of(), Map.of("test", true), CommandOrigin.MANUAL,
                null, idempotencyKey);
    }

    private Fixture fixture(ProjectMemberRole role) {
        return createFixture(true, role);
    }

    private Fixture fixtureWithoutAuthorMembership() {
        return createFixture(false, ProjectMemberRole.EDITOR);
    }

    private Fixture createFixture(boolean addAuthorMembership, ProjectMemberRole role) {
        return transactionTemplate.execute(status -> {
            UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            UserAccount author = userAccountRepository.save(UserAccount.create(
                    "command-test-" + UUID.randomUUID(), "Command Test", "command-" + UUID.randomUUID() + "@example.test"));
            Project project = projectRepository.save(Project.create(owner, "Command Test", "test"));
            UmlModel model = modelRepository.save(UmlModel.create(project, "Command Model"));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            if (addAuthorMembership) {
                projectMemberRepository.save(ProjectMember.create(project, author, role));
            }
            return new Fixture(project, model, new com.diagramador.command.Author(author.getId(), "command-test"));
        });
    }

    private Fixture createFixtureWithoutAuthorMembership() {
        return transactionTemplate.execute(status -> {
            UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            UserAccount author = userAccountRepository.save(UserAccount.create(
                    "command-nonmember-" + UUID.randomUUID(), "Non Member", "nonmember-" + UUID.randomUUID() + "@example.test"));
            Project project = projectRepository.save(Project.create(owner, "Command Test", "test"));
            UmlModel model = modelRepository.save(UmlModel.create(project, "Command Model"));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            return new Fixture(project, model, new com.diagramador.command.Author(author.getId(), "non-member"));
        });
    }

    private record Fixture(Project project, UmlModel model, com.diagramador.command.Author author) {
    }

    private record AuthorId(UUID id) {
    }

    @TestConfiguration
    static class TestHandlerConfiguration {

        @Bean
        CommandHandler testCommandHandler() {
            return new CommandHandler() {
                @Override
                public String type() {
                    return "test.noop";
                }

                @Override
                public void apply(Context context) {
                    // Test-only handler proves the service port without registering a product command.
                }
            };
        }
    }
}
