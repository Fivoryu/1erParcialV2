package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.command.Author;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.ErrorCode;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.UmlModel;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.ModelVersionRepository;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.TypeReferenceRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.persistence.UserAccountRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class T3aCommandHandlersTest extends AbstractPostgresIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final CommandService commandService;
    private final UserAccountRepository userAccountRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UmlModelRepository modelRepository;
    private final ClassifierRepository classifierRepository;
    private final UmlAttributeRepository attributeRepository;
    private final UmlOperationRepository operationRepository;
    private final OperationParameterRepository parameterRepository;
    private final UmlConstraintRepository constraintRepository;
    private final TypeReferenceRepository typeReferenceRepository;
    private final ModelVersionRepository versionRepository;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    T3aCommandHandlersTest(CommandService commandService, UserAccountRepository userAccountRepository,
            ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository,
            UmlModelRepository modelRepository, ClassifierRepository classifierRepository,
            UmlAttributeRepository attributeRepository, UmlOperationRepository operationRepository,
            OperationParameterRepository parameterRepository, UmlConstraintRepository constraintRepository,
            TypeReferenceRepository typeReferenceRepository, ModelVersionRepository versionRepository,
            TransactionTemplate transactionTemplate) {
        this.commandService = commandService;
        this.userAccountRepository = userAccountRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.modelRepository = modelRepository;
        this.classifierRepository = classifierRepository;
        this.attributeRepository = attributeRepository;
        this.operationRepository = operationRepository;
        this.parameterRepository = parameterRepository;
        this.constraintRepository = constraintRepository;
        this.typeReferenceRepository = typeReferenceRepository;
        this.versionRepository = versionRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Test
    void allThirteenCommandsPersistTheirHappyPathEffects() {
        Fixture fixture = fixture();
        UUID classifierId = UUID.randomUUID();
        UUID secondClassifierId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID parameterId = UUID.randomUUID();
        UUID constraintId = UUID.randomUUID();

        assertAccepted(command(fixture, "classifier.create", 0, Map.of("id", classifierId, "name", "Customer",
                "kind", "CLASS"), List.of()));
        assertAccepted(command(fixture, "classifier.create", 1, Map.of("id", secondClassifierId, "name", "Account",
                "kind", "CLASS"), List.of()));
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getName()).isEqualTo("Customer");

        assertAccepted(command(fixture, "classifier.rename", 2, Map.of("name", "Client"), target("classifier", classifierId)));
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getName()).isEqualTo("Client");
        assertAccepted(command(fixture, "classifier.setAbstract", 3, Map.of("isAbstract", true),
                target("classifier", classifierId)));
        assertThat(classifierRepository.findById(classifierId).orElseThrow())
                .satisfies(classifier -> {
                    assertThat(classifier.getKind()).isEqualTo(ClassifierKind.ABSTRACT_CLASS);
                    assertThat(classifier.isAbstract()).isTrue();
                });

        assertAccepted(command(fixture, "attribute.add", 4, Map.of("id", attributeId, "name", "name",
                "type", Map.of("primitiveName", "String")), target("classifier", classifierId)));
        assertThat(attributeRepository.findById(attributeId).orElseThrow().getName()).isEqualTo("name");
        assertAccepted(command(fixture, "attribute.update", 5, Map.of("name", "displayName", "defaultValue", "n/a"),
                target("attribute", attributeId)));
        assertThat(attributeRepository.findById(attributeId).orElseThrow())
                .satisfies(attribute -> {
                    assertThat(attribute.getName()).isEqualTo("displayName");
                    assertThat(attribute.getDefaultValue()).isEqualTo("n/a");
                });

        assertAccepted(command(fixture, "operation.add", 6, Map.of("id", operationId, "name", "save",
                "returnType", Map.of("primitiveName", "String")), target("classifier", classifierId)));
        assertThat(operationRepository.findById(operationId).orElseThrow().getName()).isEqualTo("save");
        assertAccepted(command(fixture, "operation.update", 7, Map.of("name", "persist", "isStatic", true),
                target("operation", operationId)));
        assertThat(operationRepository.findById(operationId).orElseThrow())
                .satisfies(operation -> {
                    assertThat(operation.getName()).isEqualTo("persist");
                    assertThat(operation.isStatic()).isTrue();
                });

        assertAccepted(command(fixture, "parameter.add", 8, Map.of("id", parameterId, "name", "request",
                "type", Map.of("classifierId", secondClassifierId)), target("operation", operationId)));
        assertThat(parameterRepository.findById(parameterId).orElseThrow().getName()).isEqualTo("request");
        assertAccepted(command(fixture, "constraint.add", 9, Map.of("id", constraintId, "ownerType", "CLASSIFIER",
                "ownerId", classifierId, "expression", "self.name != null"), target("classifier", classifierId)));
        assertThat(constraintRepository.findById(constraintId).orElseThrow().getExpression()).isEqualTo("self.name != null");

        assertAccepted(command(fixture, "parameter.remove", 10, Map.of(), target("parameter", parameterId)));
        assertThat(parameterRepository.findById(parameterId)).isEmpty();
        assertAccepted(command(fixture, "constraint.remove", 11, Map.of(), target("constraint", constraintId)));
        assertThat(constraintRepository.findById(constraintId)).isEmpty();
        assertAccepted(command(fixture, "attribute.remove", 12, Map.of(), target("attribute", attributeId)));
        assertThat(attributeRepository.findById(attributeId)).isEmpty();
        assertAccepted(command(fixture, "operation.remove", 13, Map.of(), target("operation", operationId)));
        assertThat(operationRepository.findById(operationId)).isEmpty();
        assertThat(modelRepository.findById(fixture.model().getId()).orElseThrow().getCurrentRevision()).isEqualTo(14L);
        assertThat(versionRepository.countByModelId(fixture.model().getId())).isEqualTo(14L);
    }

    @Test
    void classifierAbstractKindAndFlagCannotDivergeAndInterfaceRejectsSetAbstract() {
        Fixture fixture = fixture();
        UUID abstractId = UUID.randomUUID();
        UUID interfaceId = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0, Map.of("id", abstractId, "name", "Base",
                "kind", "ABSTRACT_CLASS"), List.of()));
        assertThat(classifierRepository.findById(abstractId).orElseThrow())
                .satisfies(classifier -> {
                    assertThat(classifier.getKind()).isEqualTo(ClassifierKind.ABSTRACT_CLASS);
                    assertThat(classifier.isAbstract()).isTrue();
                });
        assertAccepted(command(fixture, "classifier.setAbstract", 1, Map.of("isAbstract", false),
                target("classifier", abstractId)));
        assertThat(classifierRepository.findById(abstractId).orElseThrow())
                .satisfies(classifier -> {
                    assertThat(classifier.getKind()).isEqualTo(ClassifierKind.CLASS);
                    assertThat(classifier.isAbstract()).isFalse();
                });
        assertAccepted(command(fixture, "classifier.create", 2, Map.of("id", interfaceId, "name", "Port",
                "kind", "INTERFACE"), List.of()));
        CommandResult rejected = command(fixture, "classifier.setAbstract", 3, Map.of("isAbstract", false),
                target("classifier", interfaceId));
        assertRejected(rejected, ErrorCode.PRECONDITION_FAILED, "INTERFACE");
        Classifier persisted = classifierRepository.findById(interfaceId).orElseThrow();
        assertThat(persisted.getKind()).isEqualTo(ClassifierKind.INTERFACE);
        assertThat(persisted.isAbstract()).isFalse();
    }

    @Test
    void duplicateClassifierNameIsRejectedOnCreateAndRename() {
        Fixture fixture = fixture();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0, Map.of("id", first, "name", "Usuario", "kind", "CLASS"), List.of()));
        CommandResult duplicateCreate = command(fixture, "classifier.create", 1,
                Map.of("id", second, "name", "Usuario", "kind", "CLASS"), List.of());
        assertRejected(duplicateCreate, ErrorCode.PRECONDITION_FAILED, "Usuario", first.toString());

        assertAccepted(command(fixture, "classifier.create", 1, Map.of("id", second, "name", "Cuenta", "kind", "CLASS"), List.of()));
        CommandResult duplicateRename = command(fixture, "classifier.rename", 2, Map.of("name", "Usuario"),
                target("classifier", second));
        assertRejected(duplicateRename, ErrorCode.PRECONDITION_FAILED, "Usuario", first.toString());
    }

    @Test
    void rejectedCasesNameTheBaselineConstructs() {
        Fixture fixture = fixture();
        UUID classifierId = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0, Map.of("id", classifierId, "name", "C", "kind", "CLASS"), List.of()));

        assertRejected(command(fixture, "attribute.add", 1, Map.of("id", UUID.randomUUID(), "name", "x",
                "type", Map.of("primitiveName", "String"), "visibility", "PROTECTED"), target("classifier", classifierId)),
                ErrorCode.PRECONDITION_FAILED, "BL-VIS-03");
        assertRejected(command(fixture, "attribute.add", 1, Map.of("id", UUID.randomUUID(), "name", "x",
                "type", Map.of("primitiveName", "String"), "visibility", "PACKAGE"), target("classifier", classifierId)),
                ErrorCode.PRECONDITION_FAILED, "BL-VIS-04");
        assertRejected(command(fixture, "attribute.add", 1, Map.of("id", UUID.randomUUID(), "name", "x",
                "type", Map.of("primitiveName", "String"), "multiplicity", "RANGE"), target("classifier", classifierId)),
                ErrorCode.PRECONDITION_FAILED, "BL-REL-07", "GAP-05");
        assertRejected(command(fixture, "attribute.add", 1, Map.of("id", UUID.randomUUID(), "name", "x",
                "type", Map.of("primitiveName", "String"), "isStatic", true), target("classifier", classifierId)),
                ErrorCode.PRECONDITION_FAILED, "BL-FEA-05");
        assertRejected(command(fixture, "attribute.add", 1, Map.of("id", UUID.randomUUID(), "name", "x",
                "type", Map.of("primitiveName", "String"), "isAbstract", true), target("classifier", classifierId)),
                ErrorCode.PRECONDITION_FAILED, "BL-FEA-06");
        assertRejected(command(fixture, "constraint.add", 1, Map.of("id", UUID.randomUUID(), "ownerType", "ATTRIBUTE",
                "ownerId", classifierId, "expression", "x"), target("classifier", classifierId)),
                ErrorCode.PRECONDITION_FAILED, "ATTRIBUTE");
        assertRejected(command(fixture, "classifier.create", 1, Map.of("id", UUID.randomUUID(), "name", "E",
                "kind", "ENUMERATION"), List.of()), ErrorCode.PRECONDITION_FAILED, "BL-CLS-04", "GAP-02");
        assertRejected(command(fixture, "classifier.create", 1, Map.of("id", UUID.randomUUID(), "name", "A",
                "kind", "CLASS", "isAbstract", true), List.of()), ErrorCode.INVALID_PAYLOAD, "isAbstract");
    }

    @Test
    void duplicateIdsAreRejectedAndTypeReferencesAreModelScopedAndReusable() {
        Fixture fixture = fixture();
        UUID classifierId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0, Map.of("id", classifierId, "name", "C", "kind", "CLASS"), List.of()));
        assertAccepted(command(fixture, "attribute.add", 1, Map.of("id", attributeId, "name", "first",
                "type", Map.of("primitiveName", "String")), target("classifier", classifierId)));
        CommandResult duplicateId = command(fixture, "attribute.add", 2, Map.of("id", attributeId, "name", "second",
                "type", Map.of("primitiveName", "String")), target("classifier", classifierId));
        assertRejected(duplicateId, ErrorCode.PRECONDITION_FAILED, attributeId.toString());

        assertAccepted(command(fixture, "attribute.add", 2, Map.of("id", UUID.randomUUID(), "name", "second",
                "type", Map.of("primitiveName", "String")), target("classifier", classifierId)));
        assertThat(typeReferenceRepository.findByModelId(fixture.model().getId())).as("reused primitive type_reference row")
                .hasSize(1);
    }

    @Test
    void unresolvedClassifierTypeAndTakenOrdinalAreRejected() {
        Fixture fixture = fixture();
        UUID classifierId = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0, Map.of("id", classifierId, "name", "C", "kind", "CLASS"), List.of()));
        CommandResult unresolvedType = command(fixture, "attribute.add", 1, Map.of("id", UUID.randomUUID(), "name", "x",
                "type", Map.of("classifierId", UUID.randomUUID())), target("classifier", classifierId));
        assertRejected(unresolvedType, ErrorCode.PRECONDITION_FAILED, "classifierId");
        UUID firstAttribute = UUID.randomUUID();
        assertAccepted(command(fixture, "attribute.add", 1, Map.of("id", firstAttribute, "name", "x",
                "type", Map.of("primitiveName", "String"), "ordinal", 0), target("classifier", classifierId)));
        CommandResult takenOrdinal = command(fixture, "attribute.add", 2, Map.of("id", UUID.randomUUID(), "name", "y",
                "type", Map.of("primitiveName", "String"), "ordinal", 0), target("classifier", classifierId));
        assertRejected(takenOrdinal, ErrorCode.PRECONDITION_FAILED, "ordinal", "0");
    }

    private CommandResult command(Fixture fixture, String type, long revision, Object payload, List<TargetValue> targets) {
        return commandService.execute(new OperationEnvelope(UUID.randomUUID(), type, fixture.author(), fixture.project().getId(),
                fixture.model().getId(), revision, targets.stream().map(value -> new com.diagramador.command.Target(value.scope(), value.id())).toList(),
                List.of(), payload, CommandOrigin.MANUAL, null, UUID.randomUUID().toString()));
    }

    private void assertAccepted(CommandResult result) {
        assertThat(result.status()).withFailMessage("diagnostics: %s", result.diagnostics()).isEqualTo(CommandStatus.ACCEPTED);
    }

    private void assertRejected(CommandResult result, ErrorCode code, String... fragments) {
        assertThat(result.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(result.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(code);
            assertThat(diagnostic.message()).contains(fragments);
        });
    }

    private List<TargetValue> target(String scope, UUID id) {
        return List.of(new TargetValue(scope, id));
    }

    private Fixture fixture() {
        return transactionTemplate.execute(status -> {
            com.diagramador.domain.UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            com.diagramador.domain.UserAccount author = userAccountRepository.save(com.diagramador.domain.UserAccount.create(
                    "t3a-" + UUID.randomUUID(), "T3a", "t3a-" + UUID.randomUUID() + "@example.test"));
            Project project = projectRepository.save(Project.create(owner, "T3a", "commands"));
            UmlModel model = modelRepository.save(UmlModel.create(project, "T3a Model"));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            projectMemberRepository.save(ProjectMember.create(project, author, ProjectMemberRole.EDITOR));
            return new Fixture(project, model, new Author(author.getId(), "t3a"));
        });
    }

    private record Fixture(Project project, UmlModel model, Author author) {
    }

    private record TargetValue(String scope, UUID id) {
    }
}
