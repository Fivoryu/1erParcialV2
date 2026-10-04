package com.diagramador.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.command.Author;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.command.Target;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.persistence.DiagramViewRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
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
 * IA4-07: the model resource the page loads carries the persisted view_style rows additively
 * (top-level list; the legacy ModelResource constructor keeps an empty list so the XMI
 * emitter/reader path is untouched).
 */
@SpringBootTest
class ModelResourceViewStyleTest {

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

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private CommandService commandService;
    @Autowired
    private ProjectApiService projectApiService;
    @Autowired
    private CurrentUserContext currentUserContext;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectMemberRepository projectMemberRepository;
    @Autowired
    private UmlModelRepository modelRepository;
    @Autowired
    private DiagramViewRepository viewRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void clearCurrentUser() {
        currentUserContext.clear();
    }

    @Test
    void modelResourceExposesPersistedViewStyleRowsAdditively() {
        Fixture fixture = fixture("view-style-resource");
        UUID sourceId = createClassifier(fixture, 0, "Source");
        UUID targetId = createClassifier(fixture, 1, "Target");
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> relationshipPayload = new java.util.HashMap<>();
        relationshipPayload.put("id", relationshipId.toString());
        relationshipPayload.put("kind", "ASSOCIATION");
        relationshipPayload.put("name", null);
        relationshipPayload.put("source", end(sourceId));
        relationshipPayload.put("target", end(targetId));
        relationshipPayload.put("waypoints", List.of());
        accept(command(fixture, 2, "relationship.create", List.of(
                new Target("classifier", sourceId), new Target("classifier", targetId)), relationshipPayload));
        accept(command(fixture, 3, "style.set", List.of(), Map.of(
                "viewId", fixture.view().getId().toString(),
                "targetId", relationshipId.toString(),
                "routing", "ORTHOGONAL",
                "cornerRadius", 8,
                "dashed", true)));

        currentUserContext.set(new CurrentUser(fixture.author().userId(), "viewstyle", "ViewStyle"));
        ModelResource model = projectApiService.getModel(fixture.project().getId());

        assertThat(model.viewStyle()).hasSize(1);
        ModelResource.ViewStyleResource style = model.viewStyle().get(0);
        assertThat(style.viewId()).isEqualTo(fixture.view().getId());
        assertThat(style.targetId()).isEqualTo(relationshipId);
        assertThat(style.routing()).isEqualTo("ORTHOGONAL");
        assertThat(style.cornerRadius()).isEqualTo(8);
        assertThat(style.strokeWidth()).isEqualTo(2);
        assertThat(style.dashed()).isTrue();
        // Semantic resources stay untouched: the style list is additive.
        assertThat(model.relationships()).hasSize(1);

        accept(command(fixture, 4, "style.set", List.of(), Map.of(
                "viewId", fixture.view().getId().toString(),
                "targetId", relationshipId.toString(),
                "routing", "FREE")));
        ModelResource upserted = projectApiService.getModel(fixture.project().getId());
        assertThat(upserted.viewStyle()).hasSize(1);
        assertThat(upserted.viewStyle().get(0).routing()).isEqualTo("FREE");
    }

    @Test
    void theLegacyModelResourceConstructorKeepsAnEmptyViewStyleList() {
        ModelResource legacy = new ModelResource(UUID.randomUUID(), UUID.randomUUID(), 1L, UUID.randomUUID(),
                List.of(), List.of(), List.of());
        assertThat(legacy.viewStyle()).isEmpty();
    }

    private Map<String, Object> end(UUID classifierId) {
        Map<String, Object> end = new java.util.HashMap<>();
        end.put("classifierId", classifierId.toString());
        end.put("role", null);
        end.put("multiplicity", "EXACTLY_ONE");
        end.put("navigability", "UNSPECIFIED");
        return end;
    }

    private UUID createClassifier(Fixture fixture, long revision, String name) {
        UUID id = UUID.randomUUID();
        accept(command(fixture, revision, "classifier.create", List.of(), Map.of(
                "id", id.toString(), "name", name, "kind", "CLASS")));
        return id;
    }

    private void accept(com.diagramador.command.CommandResult result) {
        assertThat(result.status()).withFailMessage("diagnostics: %s", result.diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);
    }

    private com.diagramador.command.CommandResult command(Fixture fixture, long revision, String type,
            List<Target> targets, Map<String, Object> payload) {
        return commandService.execute(new OperationEnvelope(UUID.randomUUID(), type, fixture.author(),
                fixture.project().getId(), fixture.model().getId(), revision, targets, List.of(), payload,
                CommandOrigin.MANUAL, null, UUID.randomUUID().toString()));
    }

    private Fixture fixture(String label) {
        return transactionTemplate.execute(status -> {
            UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            UserAccount author = userAccountRepository.save(
                    UserAccount.create("viewstyle-" + UUID.randomUUID(), "ViewStyle",
                            "viewstyle-" + UUID.randomUUID() + "@example.test"));
            Project project = projectRepository.save(Project.create(owner, "ViewStyle " + label,
                    "model resource view_style test"));
            UmlModel model = modelRepository.save(UmlModel.create(project, "ViewStyle Model"));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            projectMemberRepository.save(ProjectMember.create(project, author, ProjectMemberRole.EDITOR));
            DiagramView view = viewRepository.save(DiagramView.create(model, "Main", "CLASS", true));
            return new Fixture(project, model, new Author(author.getId(), "viewstyle"), view);
        });
    }

    private record Fixture(Project project, UmlModel model, Author author, DiagramView view) {
    }
}
