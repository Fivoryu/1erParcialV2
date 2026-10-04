package com.diagramador.undo;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.assistant.AssistantApplyOp;
import com.diagramador.assistant.AssistantApplyRequest;
import com.diagramador.assistant.AssistantApplyResponse;
import com.diagramador.assistant.AssistantApplyService;
import com.diagramador.api.ProjectApiService;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.UmlModel;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UserAccountRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * IA4-04 deviation (b) closed: commands applied by AssistantApplyService bypass the HTTP
 * capture hook, so the apply flow itself must record undo_log entries with the same semantics
 * as the manual path (pre-state of the envelope targets BEFORE the step executes).
 */
@SpringBootTest
class AssistantUndoIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private final ProjectApiService projectApiService;
    private final AssistantApplyService assistantApplyService;
    private final UndoService undoService;
    private final UndoLogRepository undoLogRepository;
    private final ProjectRepository projectRepository;
    private final UmlModelRepository modelRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserAccountRepository userAccountRepository;
    private final ClassifierRepository classifierRepository;
    private final UmlAttributeRepository attributeRepository;
    private final com.diagramador.persistence.ModelVersionRepository versionRepository;
    private final CurrentUserContext currentUserContext;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    AssistantUndoIntegrationTest(ProjectApiService projectApiService, AssistantApplyService assistantApplyService,
            UndoService undoService, UndoLogRepository undoLogRepository, ProjectRepository projectRepository,
            UmlModelRepository modelRepository, ProjectMemberRepository projectMemberRepository,
            UserAccountRepository userAccountRepository, ClassifierRepository classifierRepository,
            UmlAttributeRepository attributeRepository, CurrentUserContext currentUserContext,
            com.diagramador.persistence.ModelVersionRepository versionRepository,
            TransactionTemplate transactionTemplate) {
        this.projectApiService = projectApiService;
        this.assistantApplyService = assistantApplyService;
        this.undoService = undoService;
        this.undoLogRepository = undoLogRepository;
        this.projectRepository = projectRepository;
        this.modelRepository = modelRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.userAccountRepository = userAccountRepository;
        this.classifierRepository = classifierRepository;
        this.attributeRepository = attributeRepository;
        this.versionRepository = versionRepository;
        this.currentUserContext = currentUserContext;
        this.transactionTemplate = transactionTemplate;
    }

    @AfterEach
    void clearSession() {
        currentUserContext.clear();
    }

    @Test
    void undoRemovesAClassifierCreatedThroughAnAssistantPlan() {
        Fixture fixture = fixture("assistant-undo-create");

        AssistantApplyResponse applied = assistantApplyService.apply(fixture.project().getId(),
                new AssistantApplyRequest(List.of(new AssistantApplyOp("classifier.create", null,
                        Map.of("name", "Order"))), 0L));

        assertThat(applied.steps()).hasSize(1);
        assertThat(applied.steps().get(0).status()).isEqualTo("ACCEPTED");
        UUID classifierId = createdClassifierId(fixture);
        assertThat(classifierRepository.findById(classifierId)).isPresent();

        UndoResult undone = undoService.undo(fixture.project().getId());

        assertThat(undone.status()).isEqualTo("ACCEPTED");
        assertThat(classifierRepository.findById(classifierId)).isEmpty();
    }

    @Test
    void undoRevertsAnAiAppliedAttributeAdd() {
        Fixture fixture = fixture("assistant-undo-attribute");
        UUID classifierId = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0,
                Map.of("id", classifierId, "name", "Order", "kind", "CLASS"), List.of()));

        AssistantApplyResponse applied = assistantApplyService.apply(fixture.project().getId(),
                new AssistantApplyRequest(List.of(new AssistantApplyOp("attribute.add", classifierId,
                        Map.of("name", "number", "type", "String"))), 1L));

        assertThat(applied.steps().get(0).status()).isEqualTo("ACCEPTED");
        assertThat(attributeRepository.findByClassifierId(classifierId)).hasSize(1);

        UndoResult undone = undoService.undo(fixture.project().getId());

        assertThat(undone.status()).isEqualTo("ACCEPTED");
        assertThat(attributeRepository.findByClassifierId(classifierId)).isEmpty();
        assertThat(classifierRepository.findById(classifierId)).isPresent();
    }

    @Test
    void redoReappliesTheAiStepAfterItWasUndone() {
        Fixture fixture = fixture("assistant-undo-redo");
        UUID classifierId = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0,
                Map.of("id", classifierId, "name", "Order", "kind", "CLASS"), List.of()));

        AssistantApplyResponse applied = assistantApplyService.apply(fixture.project().getId(),
                new AssistantApplyRequest(List.of(new AssistantApplyOp("attribute.add", classifierId,
                        Map.of("name", "number", "type", "String"))), 1L));
        assertThat(applied.steps().get(0).status()).isEqualTo("ACCEPTED");

        assertThat(undoService.undo(fixture.project().getId()).status()).isEqualTo("ACCEPTED");
        assertThat(attributeRepository.findByClassifierId(classifierId)).isEmpty();

        UndoResult redone = undoService.redo(fixture.project().getId());

        assertThat(redone.status()).isEqualTo("ACCEPTED");
        List<com.diagramador.domain.UmlAttribute> attributes = attributeRepository.findByClassifierId(classifierId);
        assertThat(attributes).hasSize(1);
        assertThat(attributes.get(0).getName()).isEqualTo("number");
    }

    // --- helpers ------------------------------------------------------------------------

    private UUID createdClassifierId(Fixture fixture) {
        return classifierRepository.findByModelId(fixture.model().getId()).stream()
                .filter(classifier -> "Order".equals(classifier.getName()))
                .findFirst().orElseThrow().getId();
    }

    private CommandResult command(Fixture fixture, String type, long revision, Map<String, Object> payload,
            List<com.diagramador.command.Target> targets) {
        return projectApiService.executeCommand(fixture.project().getId(), new OperationEnvelope(
                UUID.randomUUID(), type, new com.diagramador.command.Author(fixture.author().getId(), "author"),
                fixture.project().getId(), fixture.model().getId(), revision, targets, List.of(), payload,
                CommandOrigin.MANUAL, null, UUID.randomUUID().toString()));
    }

    private static void assertAccepted(CommandResult result) {
        assertThat(result.status()).withFailMessage("diagnostics: %s", result.diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);
    }

    private Fixture fixture(String label) {
        return transactionTemplate.execute(status -> {
            com.diagramador.domain.UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            com.diagramador.domain.UserAccount author = userAccountRepository.save(
                    com.diagramador.domain.UserAccount.create("ai-undo-" + UUID.randomUUID(), "AI Undo Author",
                            "ai-undo-" + UUID.randomUUID() + "@example.test"));
            Project project = projectRepository.save(Project.create(owner, "AI Undo " + label, "assistant tests"));
            UmlModel model = modelRepository.save(UmlModel.create(project, "AI Undo Model " + label));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            projectMemberRepository.save(ProjectMember.create(project, author, ProjectMemberRole.EDITOR));
            // Real flows get their initial version row from the project.create command; the
            // assistant apply path re-reads getModel, which requires one to exist.
            versionRepository.save(com.diagramador.domain.ModelVersion.create(model, 0L, null, "0".repeat(64),
                    com.diagramador.command.ValidationState.VALID, owner));
            currentUserContext.set(new CurrentUser(author.getId(), author.getUsername(), "Author"));
            return new Fixture(project, model, author);
        });
    }

    private record Fixture(Project project, UmlModel model, com.diagramador.domain.UserAccount author) {
    }
}
