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
import com.diagramador.domain.AttributeStereotype;
import com.diagramador.domain.ClassifierStereotype;
import com.diagramador.domain.OperationStereotype;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.UmlModel;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.persistence.UserAccountRepository;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/** T3: stereotypes from a fixed list on classifiers, attributes and operations (S5a). */
@SpringBootTest
class StereotypeCommandHandlersTest extends AbstractPostgresIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Map<String, Object> STRING_TYPE = Map.of("primitiveName", "String");

    private final CommandService commandService;
    private final UserAccountRepository userAccountRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UmlModelRepository modelRepository;
    private final ClassifierRepository classifierRepository;
    private final UmlAttributeRepository attributeRepository;
    private final UmlOperationRepository operationRepository;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    StereotypeCommandHandlersTest(CommandService commandService, UserAccountRepository userAccountRepository,
            ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository,
            UmlModelRepository modelRepository, ClassifierRepository classifierRepository,
            UmlAttributeRepository attributeRepository, UmlOperationRepository operationRepository,
            TransactionTemplate transactionTemplate) {
        this.commandService = commandService;
        this.userAccountRepository = userAccountRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.modelRepository = modelRepository;
        this.classifierRepository = classifierRepository;
        this.attributeRepository = attributeRepository;
        this.operationRepository = operationRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Test
    void everyClassifierStereotypeOfTheListCanBeSetOnAClassAndCleared() {
        Fixture fixture = fixture();
        UUID classifierId = createClass(fixture, "CLASS");
        long revision = 1;
        for (ClassifierStereotype stereotype : ClassifierStereotype.values()) {
            assertAccepted(command(fixture, "classifier.stereotype.set", revision++,
                    Map.of("stereotype", stereotype.name()), target("classifier", classifierId)));
            assertThat(classifierRepository.findById(classifierId).orElseThrow().getStereotype())
                    .isEqualTo(stereotype.name());
        }
        assertAccepted(command(fixture, "classifier.stereotype.set", revision,
                Collections.singletonMap("stereotype", null), target("classifier", classifierId)));
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getStereotype()).isNull();
    }

    @Test
    void classifierCreateAcceptsAStereotypeOnAClassAndAnAbstractClassAndKeepsNoneByDefault() {
        Fixture fixture = fixture();
        UUID plain = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        UUID abstractService = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0,
                Map.of("id", plain, "name", "Plain", "kind", "CLASS"), List.of()));
        assertAccepted(command(fixture, "classifier.create", 1,
                Map.of("id", entity, "name", "Customer", "kind", "CLASS", "stereotype", "entity"), List.of()));
        assertAccepted(command(fixture, "classifier.create", 2,
                Map.of("id", abstractService, "name", "Base", "kind", "ABSTRACT_CLASS", "stereotype", "SERVICE"),
                List.of()));

        assertThat(classifierRepository.findById(plain).orElseThrow().getStereotype()).isNull();
        assertThat(classifierRepository.findById(entity).orElseThrow().getStereotype()).isEqualTo("ENTITY");
        assertThat(classifierRepository.findById(abstractService).orElseThrow().getStereotype())
                .isEqualTo("SERVICE");
    }

    @Test
    void aStereotypeOnAnInterfaceEnumerationOrPackageIsRejected() {
        Fixture fixture = fixture();
        long revision = 0;
        for (String kind : List.of("INTERFACE", "ENUMERATION", "PACKAGE")) {
            assertRejected(command(fixture, "classifier.create", revision,
                    Map.of("id", UUID.randomUUID(), "name", "K" + kind, "kind", kind, "stereotype", "ENTITY"),
                    List.of()), ErrorCode.INVALID_PAYLOAD, "only to classes", kind);
            UUID classifierId = createClassifierOfKind(fixture, revision++, kind);
            assertRejected(command(fixture, "classifier.stereotype.set", revision,
                    Map.of("stereotype", "ENTITY"), target("classifier", classifierId)),
                    ErrorCode.INVALID_PAYLOAD, "only to classes", kind);
            assertThat(classifierRepository.findById(classifierId).orElseThrow().getStereotype()).isNull();
        }
    }

    @Test
    void anUnknownClassifierStereotypeIsRejectedAndNamed() {
        Fixture fixture = fixture();
        UUID classifierId = createClass(fixture, "CLASS");

        assertRejected(command(fixture, "classifier.stereotype.set", 1, Map.of("stereotype", "teleport"),
                target("classifier", classifierId)), ErrorCode.INVALID_PAYLOAD, "teleport");
        assertRejected(command(fixture, "classifier.create", 1,
                Map.of("id", UUID.randomUUID(), "name", "X", "kind", "CLASS", "stereotype", "INCLUDE"), List.of()),
                ErrorCode.INVALID_PAYLOAD, "INCLUDE");
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getStereotype()).isNull();
    }

    @Test
    void classifierStereotypeSetRequiresTheFieldAndRejectsUnknownFields() {
        Fixture fixture = fixture();
        UUID classifierId = createClass(fixture, "CLASS");

        assertRejected(command(fixture, "classifier.stereotype.set", 1, Map.of(), target("classifier", classifierId)),
                ErrorCode.INVALID_PAYLOAD, "stereotype");
        assertRejected(command(fixture, "classifier.stereotype.set", 1,
                Map.of("stereotype", "ENTITY", "color", "red"), target("classifier", classifierId)),
                ErrorCode.INVALID_PAYLOAD, "color");
    }

    @Test
    void everyAttributeStereotypeOfTheListCanBeAddedUpdatedAndCleared() {
        Fixture fixture = fixture();
        UUID classifierId = createClass(fixture, "CLASS");
        long revision = 1;
        for (AttributeStereotype stereotype : AttributeStereotype.values()) {
            UUID attributeId = UUID.randomUUID();
            assertAccepted(command(fixture, "attribute.add", revision++,
                    Map.of("id", attributeId, "name", "a" + stereotype, "type", STRING_TYPE,
                            "stereotype", stereotype.name().toLowerCase()),
                    target("classifier", classifierId)));
            assertThat(attributeRepository.findById(attributeId).orElseThrow().getStereotype())
                    .isEqualTo(stereotype.name());
        }

        UUID attributeId = UUID.randomUUID();
        assertAccepted(command(fixture, "attribute.add", revision++,
                Map.of("id", attributeId, "name", "plain", "type", STRING_TYPE), target("classifier", classifierId)));
        assertThat(attributeRepository.findById(attributeId).orElseThrow().getStereotype()).isNull();

        assertAccepted(command(fixture, "attribute.update", revision++, Map.of("stereotype", "UNIQUE"),
                target("attribute", attributeId)));
        assertThat(attributeRepository.findById(attributeId).orElseThrow().getStereotype()).isEqualTo("UNIQUE");
        assertAccepted(command(fixture, "attribute.update", revision++, Map.of("name", "renamed"),
                target("attribute", attributeId)));
        assertThat(attributeRepository.findById(attributeId).orElseThrow()).satisfies(attribute -> {
            assertThat(attribute.getName()).isEqualTo("renamed");
            assertThat(attribute.getStereotype()).as("an update of other fields keeps it").isEqualTo("UNIQUE");
        });
        assertAccepted(command(fixture, "attribute.update", revision, Collections.singletonMap("stereotype", null),
                target("attribute", attributeId)));
        assertThat(attributeRepository.findById(attributeId).orElseThrow().getStereotype()).isNull();
    }

    @Test
    void anUnknownAttributeStereotypeIsRejectedOnAddAndOnUpdate() {
        Fixture fixture = fixture();
        UUID classifierId = createClass(fixture, "CLASS");
        UUID attributeId = UUID.randomUUID();
        assertAccepted(command(fixture, "attribute.add", 1,
                Map.of("id", attributeId, "name", "a", "type", STRING_TYPE, "stereotype", "ID"),
                target("classifier", classifierId)));

        assertRejected(command(fixture, "attribute.add", 2,
                Map.of("id", UUID.randomUUID(), "name", "b", "type", STRING_TYPE, "stereotype", "ENTITY"),
                target("classifier", classifierId)), ErrorCode.INVALID_PAYLOAD, "ENTITY");
        assertRejected(command(fixture, "attribute.update", 2, Map.of("stereotype", "QUERY"),
                target("attribute", attributeId)), ErrorCode.INVALID_PAYLOAD, "QUERY");
        assertThat(attributeRepository.findById(attributeId).orElseThrow().getStereotype()).isEqualTo("ID");
    }

    @Test
    void everyOperationStereotypeOfTheListCanBeAddedUpdatedAndCleared() {
        Fixture fixture = fixture();
        UUID classifierId = createClass(fixture, "CLASS");
        long revision = 1;
        for (OperationStereotype stereotype : OperationStereotype.values()) {
            UUID operationId = UUID.randomUUID();
            assertAccepted(command(fixture, "operation.add", revision++,
                    Map.of("id", operationId, "name", "op" + stereotype, "stereotype", stereotype.name()),
                    target("classifier", classifierId)));
            assertThat(operationRepository.findById(operationId).orElseThrow().getStereotype())
                    .isEqualTo(stereotype.name());
        }

        UUID operationId = UUID.randomUUID();
        assertAccepted(command(fixture, "operation.add", revision++, Map.of("id", operationId, "name", "plain"),
                target("classifier", classifierId)));
        assertThat(operationRepository.findById(operationId).orElseThrow().getStereotype()).isNull();

        assertAccepted(command(fixture, "operation.update", revision++, Map.of("stereotype", "destroy"),
                target("operation", operationId)));
        assertThat(operationRepository.findById(operationId).orElseThrow().getStereotype()).isEqualTo("DESTROY");
        assertAccepted(command(fixture, "operation.update", revision++, Map.of("name", "renamed"),
                target("operation", operationId)));
        assertThat(operationRepository.findById(operationId).orElseThrow().getStereotype()).isEqualTo("DESTROY");
        assertAccepted(command(fixture, "operation.update", revision, Collections.singletonMap("stereotype", null),
                target("operation", operationId)));
        assertThat(operationRepository.findById(operationId).orElseThrow().getStereotype()).isNull();
    }

    @Test
    void anUnknownOperationStereotypeIsRejectedOnAddAndOnUpdate() {
        Fixture fixture = fixture();
        UUID classifierId = createClass(fixture, "CLASS");
        UUID operationId = UUID.randomUUID();
        assertAccepted(command(fixture, "operation.add", 1,
                Map.of("id", operationId, "name", "o", "stereotype", "QUERY"), target("classifier", classifierId)));

        assertRejected(command(fixture, "operation.add", 2,
                Map.of("id", UUID.randomUUID(), "name", "p", "stereotype", "ID"), target("classifier", classifierId)),
                ErrorCode.INVALID_PAYLOAD, "'ID'");
        assertRejected(command(fixture, "operation.update", 2, Map.of("stereotype", "ENTITY"),
                target("operation", operationId)), ErrorCode.INVALID_PAYLOAD, "ENTITY");
        assertThat(operationRepository.findById(operationId).orElseThrow().getStereotype()).isEqualTo("QUERY");
    }

    @Test
    void theCommandsKeepTheirPreviousBehaviourWhenNoStereotypeIsSent() {
        Fixture fixture = fixture();
        UUID classifierId = createClass(fixture, "CLASS");
        UUID attributeId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        assertAccepted(command(fixture, "attribute.add", 1,
                Map.of("id", attributeId, "name", "a", "type", STRING_TYPE, "visibility", "PRIVATE"),
                target("classifier", classifierId)));
        assertAccepted(command(fixture, "operation.add", 2,
                Map.of("id", operationId, "name", "o", "visibility", "PROTECTED"), target("classifier", classifierId)));
        assertAccepted(command(fixture, "attribute.update", 3, Map.of("name", "b"), target("attribute", attributeId)));
        assertAccepted(command(fixture, "operation.update", 4, Map.of("name", "p"), target("operation", operationId)));
        assertAccepted(command(fixture, "classifier.rename", 5, Map.of("name", "Renamed"),
                target("classifier", classifierId)));

        assertThat(attributeRepository.findById(attributeId).orElseThrow()).satisfies(attribute -> {
            assertThat(attribute.getName()).isEqualTo("b");
            assertThat(attribute.getVisibility()).isEqualTo("PRIVATE");
            assertThat(attribute.getStereotype()).isNull();
        });
        assertThat(operationRepository.findById(operationId).orElseThrow()).satisfies(operation -> {
            assertThat(operation.getName()).isEqualTo("p");
            assertThat(operation.getVisibility()).isEqualTo("PROTECTED");
            assertThat(operation.getStereotype()).isNull();
        });
        assertThat(classifierRepository.findById(classifierId).orElseThrow()).satisfies(classifier -> {
            assertThat(classifier.getName()).isEqualTo("Renamed");
            assertThat(classifier.getStereotype()).isNull();
        });
    }

    private UUID createClass(Fixture fixture, String kind) {
        return createClassifierOfKind(fixture, 0, kind);
    }

    private UUID createClassifierOfKind(Fixture fixture, long revision, String kind) {
        UUID id = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", revision,
                Map.of("id", id, "name", "C" + kind + id.toString().substring(0, 6), "kind", kind), List.of()));
        return id;
    }

    private CommandResult command(Fixture fixture, String type, long revision, Object payload, List<Target> targets) {
        return commandService.execute(new OperationEnvelope(UUID.randomUUID(), type, fixture.author(),
                fixture.project().getId(), fixture.model().getId(), revision, targets, List.of(), payload,
                CommandOrigin.MANUAL, null, UUID.randomUUID().toString()));
    }

    private void assertAccepted(CommandResult result) {
        assertThat(result.status()).withFailMessage("diagnostics: %s", result.diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);
    }

    private void assertRejected(CommandResult result, ErrorCode code, String... fragments) {
        assertThat(result.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(result.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(code);
            assertThat(diagnostic.message()).contains(fragments);
        });
    }

    private List<Target> target(String scope, UUID id) {
        return List.of(new Target(scope, id));
    }

    private Fixture fixture() {
        return transactionTemplate.execute(status -> {
            com.diagramador.domain.UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            com.diagramador.domain.UserAccount author = userAccountRepository.save(
                    com.diagramador.domain.UserAccount.create("st-" + UUID.randomUUID(), "St",
                            "st-" + UUID.randomUUID() + "@example.test"));
            Project project = projectRepository.save(Project.create(owner, "Stereotypes", "commands"));
            UmlModel model = modelRepository.save(UmlModel.create(project, "Stereotypes Model"));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            projectMemberRepository.save(ProjectMember.create(project, author, ProjectMemberRole.EDITOR));
            return new Fixture(project, model, new Author(author.getId(), "st"));
        });
    }

    private record Fixture(Project project, UmlModel model, Author author) {
    }
}
