package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.command.Author;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.ErrorCode;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.command.Target;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.UmlModel;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UserAccountRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class T4cClassifierNamespaceHandlerTest extends AbstractPostgresIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final CommandService commandService;
    private final UserAccountRepository userAccountRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UmlModelRepository modelRepository;
    private final ClassifierRepository classifierRepository;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    T4cClassifierNamespaceHandlerTest(CommandService commandService, UserAccountRepository userAccountRepository,
            ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository,
            UmlModelRepository modelRepository, ClassifierRepository classifierRepository,
            TransactionTemplate transactionTemplate) {
        this.commandService = commandService;
        this.userAccountRepository = userAccountRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.modelRepository = modelRepository;
        this.classifierRepository = classifierRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Test
    void namespaceCanBeSetToAPackageAndCleared() {
        Fixture fixture = fixture("set-and-clear");
        UUID packageId = createClassifier(fixture, 0, "Package", ClassifierKind.PACKAGE, null);
        UUID classifierId = createClassifier(fixture, 1, "Order", ClassifierKind.CLASS, null);

        assertAccepted(setNamespace(fixture, 2, classifierId, packageId));
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getNamespace().getId())
                .isEqualTo(packageId);

        assertAccepted(setNamespace(fixture, 3, classifierId, null));
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getNamespace()).isNull();
        assertThat(currentRevision(fixture)).isEqualTo(4L);
    }

    @Test
    void namespaceRejectsAClassifierThatIsNotAPackageAndNamesTheValue() {
        Fixture fixture = fixture("non-package-namespace");
        UUID classifierId = createClassifier(fixture, 0, "Order", ClassifierKind.CLASS, null);
        UUID namespaceId = createClassifier(fixture, 1, "Not a package", ClassifierKind.CLASS, null);

        CommandResult result = setNamespace(fixture, 2, classifierId, namespaceId);

        assertRejected(result, namespaceId.toString(), "PACKAGE");
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getNamespace()).isNull();
        assertThat(currentRevision(fixture)).isEqualTo(2L);
    }

    @Test
    void namespaceRejectsAPackageFromAnotherModelAndNamesTheValue() {
        Fixture fixture = fixture("cross-model-target");
        Fixture otherModel = fixture("cross-model-package");
        UUID classifierId = createClassifier(fixture, 0, "Order", ClassifierKind.CLASS, null);
        UUID packageId = createClassifier(otherModel, 0, "Foreign package", ClassifierKind.PACKAGE, null);

        CommandResult result = setNamespace(fixture, 1, classifierId, packageId);

        assertRejected(result, packageId.toString(), fixture.model().getId().toString());
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getNamespace()).isNull();
        assertThat(currentRevision(fixture)).isEqualTo(1L);
    }

    @Test
    void namespaceRejectsTheClassifierAsItsOwnNamespaceAndNamesIt() {
        Fixture fixture = fixture("self-namespace");
        UUID packageId = createClassifier(fixture, 0, "Package", ClassifierKind.PACKAGE, null);

        CommandResult result = setNamespace(fixture, 1, packageId, packageId);

        assertRejected(result, packageId.toString(), "own namespace");
        assertThat(classifierRepository.findById(packageId).orElseThrow().getNamespace()).isNull();
        assertThat(currentRevision(fixture)).isEqualTo(1L);
    }

    @Test
    void namespaceRejectsACycleAndNamesTheClassifierThatClosesIt() {
        Fixture fixture = fixture("namespace-cycle");
        UUID outerPackageId = createClassifier(fixture, 0, "Outer", ClassifierKind.PACKAGE, null);
        UUID middlePackageId = createClassifier(fixture, 1, "Middle", ClassifierKind.PACKAGE, outerPackageId);
        UUID innerPackageId = createClassifier(fixture, 2, "Inner", ClassifierKind.PACKAGE, middlePackageId);

        CommandResult result = setNamespace(fixture, 3, outerPackageId, innerPackageId);

        assertRejected(result, outerPackageId.toString(), "cycle");
        assertThat(classifierRepository.findById(outerPackageId).orElseThrow().getNamespace()).isNull();
        assertThat(currentRevision(fixture)).isEqualTo(3L);
    }

    private UUID createClassifier(Fixture fixture, long revision, String name, ClassifierKind kind, UUID namespaceId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", UUID.randomUUID());
        payload.put("name", name);
        payload.put("kind", kind.name());
        if (namespaceId != null) {
            payload.put("namespaceId", namespaceId);
        }
        UUID classifierId = (UUID) payload.get("id");
        assertAccepted(command(fixture, "classifier.create", revision, payload, List.of()));
        return classifierId;
    }

    private CommandResult setNamespace(Fixture fixture, long revision, UUID classifierId, UUID namespaceId) {
        return command(fixture, "classifier.setNamespace", revision,
                java.util.Collections.singletonMap("namespaceId", namespaceId), target(classifierId));
    }

    private CommandResult command(Fixture fixture, String type, long revision, Object payload, List<Target> targets) {
        return commandService.execute(new OperationEnvelope(UUID.randomUUID(), type, fixture.author(),
                fixture.project().getId(), fixture.model().getId(), revision, targets, List.of(), payload,
                CommandOrigin.MANUAL, null, UUID.randomUUID().toString()));
    }

    private List<Target> target(UUID id) {
        return List.of(new Target("classifier", id));
    }

    private void assertAccepted(CommandResult result) {
        assertThat(result.status()).withFailMessage("diagnostics: %s", result.diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);
    }

    private void assertRejected(CommandResult result, String... messageFragments) {
        assertThat(result.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(result.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(diagnostic.message()).contains(messageFragments);
        });
    }

    private long currentRevision(Fixture fixture) {
        return modelRepository.findById(fixture.model().getId()).orElseThrow().getCurrentRevision();
    }

    private Fixture fixture(String label) {
        return transactionTemplate.execute(status -> {
            var owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            var author = userAccountRepository.save(com.diagramador.domain.UserAccount.create(
                    "t4c-" + UUID.randomUUID(), "T4c", "t4c-" + UUID.randomUUID() + "@example.test"));
            Project project = projectRepository.save(Project.create(owner, "T4c " + label, "namespace command tests"));
            UmlModel model = modelRepository.save(UmlModel.create(project, "T4c Model"));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            projectMemberRepository.save(ProjectMember.create(project, author, ProjectMemberRole.EDITOR));
            return new Fixture(project, model, new Author(author.getId(), "t4c"));
        });
    }

    private record Fixture(Project project, UmlModel model, Author author) {
    }
}
