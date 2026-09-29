package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.command.Author;
import com.diagramador.command.AuthorizationClassification;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.ErrorCode;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.command.Target;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.ModelCommand;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.persistence.DiagramViewRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class T4bProjectAndViewHandlersTest extends AbstractPostgresIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final CommandService commandService;
    private final UserAccountRepository userAccountRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UmlModelRepository modelRepository;
    private final DiagramViewRepository viewRepository;
    private final ModelCommandRepository commandRepository;
    private final ModelVersionRepository versionRepository;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    T4bProjectAndViewHandlersTest(CommandService commandService, UserAccountRepository userAccountRepository,
            ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository,
            UmlModelRepository modelRepository, DiagramViewRepository viewRepository,
            ModelCommandRepository commandRepository, ModelVersionRepository versionRepository,
            TransactionTemplate transactionTemplate) {
        this.commandService = commandService;
        this.userAccountRepository = userAccountRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.modelRepository = modelRepository;
        this.viewRepository = viewRepository;
        this.commandRepository = commandRepository;
        this.versionRepository = versionRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Test
    void projectCreatePersistsAggregateMembershipDefaultViewRevisionAndVersion() {
        UUID projectId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        OperationEnvelope envelope = projectCreateEnvelope(projectId, modelId, "Project create", "create-aggregate");

        CommandResult result = commandService.execute(envelope);

        assertThat(result.status()).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(result.resultRevision()).isEqualTo(1L);
        Project project = projectRepository.findById(projectId).orElseThrow();
        UmlModel model = modelRepository.findById(modelId).orElseThrow();
        assertThat(project.getOwner().getId()).isEqualTo(SEED_OWNER_ID);
        assertThat(model.getProject().getId()).isEqualTo(projectId);
        assertThat(model.getCurrentRevision()).isEqualTo(1L);
        assertThat(projectMemberRepository.findByProjectIdAndUserId(projectId, SEED_OWNER_ID))
                .get().satisfies(member -> {
                    assertThat(member.getRole()).isEqualTo(ProjectMemberRole.OWNER);
                    assertThat(member.getStatus()).isEqualTo("ACTIVE");
                });
        assertThat(viewRepository.findByModelId(modelId)).singleElement().satisfies(view -> {
            assertThat(view.isDefault()).isTrue();
            assertThat(view.getViewKind()).isEqualTo("CLASS");
        });
        assertThat(versionRepository.countByModelId(modelId)).isEqualTo(1L);
    }

    @Test
    void replayingProjectCreateReturnsOriginalResultWithoutChangingRowCounts() {
        UUID projectId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        OperationEnvelope envelope = projectCreateEnvelope(projectId, modelId, "Replay project", "replay-create");

        CommandResult first = commandService.execute(envelope);
        long projects = projectRepository.count();
        long members = projectMemberRepository.count();
        long models = modelRepository.count();
        long views = viewRepository.count();
        long commands = commandRepository.countByModelId(modelId);
        long versions = versionRepository.countByModelId(modelId);

        CommandResult second = commandService.execute(envelope);

        assertThat(second.operationId()).as("replay returns the original operationId")
                .isEqualTo(first.operationId());
        assertThat(second.status()).as("replay returns the original status")
                .isEqualTo(first.status());
        assertThat(second.resultRevision()).as("replay returns the original resultRevision")
                .isEqualTo(first.resultRevision());
        assertThat(second.authorizationClassification()).as("replay returns the original authorization")
                .isEqualTo(first.authorizationClassification());
        assertThat(projectRepository.count()).as("no second project row").isEqualTo(projects);
        assertThat(projectMemberRepository.count()).as("no second project_member row").isEqualTo(members);
        assertThat(modelRepository.count()).as("no second uml_model row").isEqualTo(models);
        assertThat(viewRepository.count()).as("no second diagram_view row").isEqualTo(views);
        assertThat(commandRepository.countByModelId(modelId)).as("no second model_command row")
                .isEqualTo(commands);
        assertThat(versionRepository.countByModelId(modelId)).as("no second model_version row")
                .isEqualTo(versions);
    }

    @Test
    void projectCreateUnknownAuthorIsUnauthorizedBeforeForeignKeyChecks() {
        UUID operationId = UUID.randomUUID();
        OperationEnvelope envelope = new OperationEnvelope(operationId, "project.create",
                new Author(UUID.randomUUID(), "unknown"), UUID.randomUUID(), UUID.randomUUID(), 0L,
                List.of(), List.of(), Map.of("name", "Unknown author project"), CommandOrigin.MANUAL, null,
                "unknown-create-author");

        CommandResult result = commandService.execute(envelope);

        assertRejected(result, ErrorCode.UNAUTHORIZED);
    }

    @Test
    void projectCreateBlankNameIsInvalidPayload() {
        OperationEnvelope envelope = projectCreateEnvelope(UUID.randomUUID(), UUID.randomUUID(), " ", "blank-name");

        CommandResult result = commandService.execute(envelope);

        assertRejected(result, ErrorCode.INVALID_PAYLOAD);
    }

    @Test
    void viewCreateSupportsClassViewsAndRejectsDuplicateIdsAndUnknownKinds() {
        Fixture fixture = existingAggregate("View project");
        UUID viewId = UUID.randomUUID();
        CommandResult created = commandService.execute(envelope(fixture, "view.create", 0L,
                Map.of("id", viewId, "name", "Alternate"), List.of(), "view-create"));
        assertThat(created.status()).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(viewRepository.findById(viewId).orElseThrow())
                .satisfies(view -> {
                    assertThat(view.getModel().getId()).isEqualTo(fixture.model().getId());
                    assertThat(view.getViewKind()).isEqualTo("CLASS");
                    assertThat(view.isDefault()).isFalse();
                });

        CommandResult duplicate = commandService.execute(envelope(fixture, "view.create", 1L,
                Map.of("id", viewId, "name", "Duplicate"), List.of(), "view-duplicate"));
        assertRejected(duplicate, ErrorCode.PRECONDITION_FAILED, viewId.toString());

        CommandResult unknownKind = commandService.execute(envelope(fixture, "view.create", 1L,
                Map.of("id", UUID.randomUUID(), "name", "Invalid", "viewKind", "SEQUENCE"), List.of(),
                "view-unknown-kind"));
        assertRejected(unknownKind, ErrorCode.PRECONDITION_FAILED, "SEQUENCE");
    }

    @Test
    void projectRenameUpdatesNameAndRejectsBlankName() {
        Fixture fixture = existingAggregate("Original project");
        List<Target> target = List.of(new Target("project", fixture.project().getId()));

        CommandResult renamed = commandService.execute(envelope(fixture, "project.rename", 0L,
                Map.of("name", "Renamed project"), target, "project-rename"));
        assertThat(renamed.status()).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(projectRepository.findById(fixture.project().getId()).orElseThrow().getName())
                .isEqualTo("Renamed project");

        CommandResult blank = commandService.execute(envelope(fixture, "project.rename", 1L,
                Map.of("name", "   "), target, "project-rename-blank"));
        assertRejected(blank, ErrorCode.INVALID_PAYLOAD);
    }

    @Test
    void commandRequiringExistingModelStillRejectsMissingModel() {
        Project project = transactionTemplate.execute(status -> {
            UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            Project created = projectRepository.save(Project.create(owner, "Missing model project", "test"));
            projectMemberRepository.save(ProjectMember.create(created, owner, ProjectMemberRole.OWNER));
            return created;
        });
        UUID missingModelId = UUID.randomUUID();
        OperationEnvelope envelope = new OperationEnvelope(UUID.randomUUID(), "project.rename",
                new Author(SEED_OWNER_ID, "seed-owner-local"), project.getId(), missingModelId, 0L,
                List.of(new Target("project", project.getId())), List.of(), Map.of("name", "No model"),
                CommandOrigin.MANUAL, null, "missing-model-rename");

        CommandResult result = commandService.execute(envelope);

        assertRejected(result, ErrorCode.PRECONDITION_FAILED, missingModelId.toString());
    }

    private OperationEnvelope projectCreateEnvelope(UUID projectId, UUID modelId, String name, String idempotencyKey) {
        return new OperationEnvelope(UUID.randomUUID(), "project.create",
                new Author(SEED_OWNER_ID, "seed-owner-local"), projectId, modelId, 0L, List.of(), List.of(),
                Map.of("name", name, "description", "Created by command", "modelName", "Domain",
                        "defaultViewName", "Class diagram"), CommandOrigin.MANUAL, null, idempotencyKey);
    }

    private CommandResult command(Fixture fixture, String type, long revision, Object payload) {
        return commandService.execute(envelope(fixture, type, revision, payload, List.of(), UUID.randomUUID().toString()));
    }

    private OperationEnvelope envelope(Fixture fixture, String type, long revision, Object payload,
            List<Target> targets, String idempotencyKey) {
        return new OperationEnvelope(UUID.randomUUID(), type, fixture.author(), fixture.project().getId(),
                fixture.model().getId(), revision, targets, List.of(), payload, CommandOrigin.MANUAL, null,
                idempotencyKey);
    }

    private Fixture existingAggregate(String name) {
        return transactionTemplate.execute(status -> {
            UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            Project project = projectRepository.save(Project.create(owner, name, "test"));
            UmlModel model = modelRepository.save(UmlModel.create(project, name + " model"));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            return new Fixture(project, model, new Author(SEED_OWNER_ID, "seed-owner-local"));
        });
    }

    private void assertRejected(CommandResult result, ErrorCode code, String... messageFragments) {
        assertThat(result.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(result.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(code);
            if (messageFragments.length > 0) {
                assertThat(diagnostic.message()).contains(messageFragments);
            }
        });
    }

    private record Fixture(Project project, UmlModel model, Author author) {
    }
}
