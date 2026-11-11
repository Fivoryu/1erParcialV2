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
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.TypeReference;
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
    void classifierSetStoreFixesAndClearsTheManualPersistenceStore() {
        Fixture fixture = fixture();
        UUID classId = UUID.randomUUID();
        UUID interfaceId = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0, Map.of("id", classId, "name", "Order",
                "kind", "CLASS"), List.of()));
        assertThat(classifierRepository.findById(classId).orElseThrow().getStoreOverride()).isNull();

        assertAccepted(command(fixture, "classifier.setStore", 1, Map.of("store", "DOCUMENT"),
                target("classifier", classId)));
        assertThat(classifierRepository.findById(classId).orElseThrow().getStoreOverride())
                .isEqualTo(com.diagramador.domain.PersistenceStore.DOCUMENT);

        assertAccepted(command(fixture, "classifier.setStore", 2, java.util.Collections.singletonMap("store", null),
                target("classifier", classId)));
        assertThat(classifierRepository.findById(classId).orElseThrow().getStoreOverride()).isNull();

        assertRejected(command(fixture, "classifier.setStore", 3, Map.of("store", "GRAPH"),
                target("classifier", classId)), ErrorCode.PRECONDITION_FAILED, "SQL, DOCUMENT");
        assertAccepted(command(fixture, "classifier.create", 3, Map.of("id", interfaceId, "name", "Port",
                "kind", "INTERFACE"), List.of()));
        assertRejected(command(fixture, "classifier.setStore", 4, Map.of("store", "SQL"),
                target("classifier", interfaceId)), ErrorCode.PRECONDITION_FAILED, "INTERFACE");
        assertThat(classifierRepository.findById(interfaceId).orElseThrow().getStoreOverride()).isNull();
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
    void acceptsAllFourUmlVisibilityLevelsOnAttributes() {
        Fixture fixture = fixture();
        UUID classifierId = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0,
                Map.of("id", classifierId, "name", "C", "kind", "CLASS"), List.of()));

        long revision = 1;
        for (String visibility : List.of("PUBLIC", "PRIVATE", "PROTECTED", "PACKAGE")) {
            assertAccepted(command(fixture, "attribute.add", revision, Map.of("id", UUID.randomUUID(),
                    "name", "a" + visibility.toLowerCase(), "type", Map.of("primitiveName", "String"),
                    "visibility", visibility), target("classifier", classifierId)));
            revision++;
        }
    }

    @Test
    void attributeRangeMultiplicityStoresNumericBounds() {
        Fixture fixture = fixture();
        UUID classifierId = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0,
                Map.of("id", classifierId, "name", "C", "kind", "CLASS"), List.of()));

        UUID bounded = UUID.randomUUID();
        UUID unbounded = UUID.randomUUID();
        UUID exact = UUID.randomUUID();
        assertAccepted(command(fixture, "attribute.add", 1, Map.of("id", bounded, "name", "a",
                "type", Map.of("primitiveName", "String"), "multiplicity", "RANGE", "lowerBound", 2,
                "upperBound", 5), target("classifier", classifierId)));
        assertAccepted(command(fixture, "attribute.add", 2, Map.of("id", unbounded, "name", "b",
                "type", Map.of("primitiveName", "String"), "multiplicity", "RANGE", "lowerBound", 2),
                target("classifier", classifierId)));
        assertAccepted(command(fixture, "attribute.add", 3, Map.of("id", exact, "name", "c",
                "type", Map.of("primitiveName", "String"), "multiplicity", "RANGE", "lowerBound", 3,
                "upperBound", 3), target("classifier", classifierId)));

        assertThat(attributeRepository.findById(bounded).orElseThrow()).satisfies(attribute -> {
            assertThat(attribute.getMultiplicity()).isEqualTo(com.diagramador.domain.MultiplicityKind.RANGE);
            assertThat(attribute.getLowerBound()).isEqualTo(2);
            assertThat(attribute.getUpperBound()).isEqualTo(5);
        });
        assertThat(attributeRepository.findById(unbounded).orElseThrow().getUpperBound()).isNull();
        assertThat(attributeRepository.findById(exact).orElseThrow().getLowerBound()).isEqualTo(3);

        // update keeps bounds not mentioned, and switching away from RANGE clears them
        assertAccepted(command(fixture, "attribute.update", 4, Map.of("upperBound", 9),
                target("attribute", bounded)));
        assertThat(attributeRepository.findById(bounded).orElseThrow().getUpperBound()).isEqualTo(9);
        assertThat(attributeRepository.findById(bounded).orElseThrow().getLowerBound()).isEqualTo(2);
        assertAccepted(command(fixture, "attribute.update", 5, Map.of("multiplicity", "ZERO_OR_MANY"),
                target("attribute", bounded)));
        assertThat(attributeRepository.findById(bounded).orElseThrow()).satisfies(attribute -> {
            assertThat(attribute.getLowerBound()).isNull();
            assertThat(attribute.getUpperBound()).isNull();
        });
    }

    @Test
    void attributeRangeBoundsAreValidated() {
        Fixture fixture = fixture();
        UUID classifierId = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0,
                Map.of("id", classifierId, "name", "C", "kind", "CLASS"), List.of()));

        assertRejected(command(fixture, "attribute.add", 1, Map.of("id", UUID.randomUUID(), "name", "x",
                "type", Map.of("primitiveName", "String"), "multiplicity", "RANGE", "lowerBound", 5,
                "upperBound", 2), target("classifier", classifierId)), ErrorCode.INVALID_PAYLOAD, "upperBound");
        assertRejected(command(fixture, "attribute.add", 1, Map.of("id", UUID.randomUUID(), "name", "x",
                "type", Map.of("primitiveName", "String"), "multiplicity", "RANGE", "lowerBound", -1),
                target("classifier", classifierId)), ErrorCode.INVALID_PAYLOAD, "lowerBound");
        assertRejected(command(fixture, "attribute.add", 1, Map.of("id", UUID.randomUUID(), "name", "x",
                "type", Map.of("primitiveName", "String"), "multiplicity", "ONE_OR_MANY", "lowerBound", 1),
                target("classifier", classifierId)), ErrorCode.INVALID_PAYLOAD, "RANGE");
    }

    @Test
    void rejectedCasesNameTheBaselineConstructs() {
        Fixture fixture = fixture();
        UUID classifierId = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0, Map.of("id", classifierId, "name", "C", "kind", "CLASS"), List.of()));

        assertRejected(command(fixture, "attribute.add", 1, Map.of("id", UUID.randomUUID(), "name", "x",
                "type", Map.of("primitiveName", "String"), "visibility", "SECRET"), target("classifier", classifierId)),
                ErrorCode.INVALID_PAYLOAD, "SECRET");
        assertRejected(command(fixture, "attribute.add", 1, Map.of("id", UUID.randomUUID(), "name", "x",
                "type", Map.of("primitiveName", "String"), "multiplicity", "RANGE"), target("classifier", classifierId)),
                ErrorCode.INVALID_PAYLOAD, "lowerBound");
        assertRejected(command(fixture, "attribute.add", 1, Map.of("id", UUID.randomUUID(), "name", "x",
                "type", Map.of("primitiveName", "String"), "isStatic", "yes"), target("classifier", classifierId)),
                ErrorCode.INVALID_PAYLOAD, "isStatic");
        assertRejected(command(fixture, "attribute.add", 1, Map.of("id", UUID.randomUUID(), "name", "x",
                "type", Map.of("primitiveName", "String"), "isAbstract", true), target("classifier", classifierId)),
                ErrorCode.PRECONDITION_FAILED, "BL-FEA-06");
        assertRejected(command(fixture, "constraint.add", 1, Map.of("id", UUID.randomUUID(), "ownerType", "ATTRIBUTE",
                "ownerId", classifierId, "expression", "x"), target("classifier", classifierId)),
                ErrorCode.PRECONDITION_FAILED, "ATTRIBUTE");
        assertRejected(command(fixture, "classifier.create", 1, Map.of("id", UUID.randomUUID(), "name", "A",
                "kind", "CLASS", "isAbstract", true), List.of()), ErrorCode.INVALID_PAYLOAD, "isAbstract");
    }

    @Test
    void enumerationStoresUntypedLiteralsAndClassesCanUseItAsType() {
        Fixture fixture = fixture();
        UUID enumId = UUID.randomUUID();
        UUID literalId = UUID.randomUUID();
        UUID classId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0, Map.of("id", enumId, "name", "Turno",
                "kind", "ENUMERATION"), List.of()));
        assertThat(classifierRepository.findById(enumId).orElseThrow().getKind())
                .isEqualTo(ClassifierKind.ENUMERATION);
        assertAccepted(command(fixture, "attribute.add", 1, Map.of("id", literalId, "name", "MANANA"),
                target("classifier", enumId)));
        assertThat(attributeRepository.findById(literalId).orElseThrow()).satisfies(literal -> {
            assertThat(literal.getKind()).isEqualTo("LITERAL");
            assertThat(literal.getTypeReference()).isNull();
        });

        assertRejected(command(fixture, "attribute.add", 2, Map.of("id", UUID.randomUUID(), "name", "TARDE",
                "type", Map.of("primitiveName", "String")), target("classifier", enumId)),
                ErrorCode.PRECONDITION_FAILED, "literal", "type");
        assertRejected(command(fixture, "attribute.add", 2, Map.of("id", UUID.randomUUID(), "name", "media tarde"),
                target("classifier", enumId)), ErrorCode.PRECONDITION_FAILED, "media tarde", "identifier");
        assertRejected(command(fixture, "attribute.update", 2, Map.of("type", Map.of("primitiveName", "String")),
                target("attribute", literalId)), ErrorCode.PRECONDITION_FAILED, "literal", "type");
        assertAccepted(command(fixture, "attribute.update", 2, Map.of("name", "NOCHE"), target("attribute", literalId)));
        assertThat(attributeRepository.findById(literalId).orElseThrow().getName()).isEqualTo("NOCHE");

        assertAccepted(command(fixture, "classifier.create", 3, Map.of("id", classId, "name", "Curso",
                "kind", "CLASS"), List.of()));
        assertAccepted(command(fixture, "attribute.add", 4, Map.of("id", attributeId, "name", "turno",
                "type", Map.of("classifierId", enumId)), target("classifier", classId)));
        assertThat(attributeRepository.findById(attributeId).orElseThrow().getKind()).isEqualTo("ATTRIBUTE");
        assertRejected(command(fixture, "attribute.add", 5, Map.of("id", UUID.randomUUID(), "name", "sinTipo"),
                target("classifier", classId)), ErrorCode.INVALID_PAYLOAD, "type");
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

    @Test
    void parameterUpdatePersistsEditableFieldsAndRejectsInvalidValues() {
        ParameterFixture fixture = parameterFixture();
        OperationParameter second = parameterRepository.findById(fixture.secondParameterId()).orElseThrow();
        UUID reusableIntegerTypeId = second.getTypeReference().getId();

        assertAccepted(command(fixture.fixture(), "parameter.update", fixture.revision(), Map.of("name", "renamed"),
                target("parameter", fixture.firstParameterId())));
        assertThat(parameterRepository.findById(fixture.firstParameterId()).orElseThrow().getName()).isEqualTo("renamed");

        assertAccepted(command(fixture.fixture(), "parameter.update", fixture.revision() + 1,
                Map.of("type", Map.of("primitiveName", "Integer")), target("parameter", fixture.firstParameterId())));
        assertThat(parameterRepository.findById(fixture.firstParameterId()).orElseThrow().getTypeReference().getId())
                .isEqualTo(reusableIntegerTypeId);

        assertAccepted(command(fixture.fixture(), "parameter.update", fixture.revision() + 2,
                Map.of("type", Map.of("classifierId", fixture.referencedClassifierId())),
                target("parameter", fixture.firstParameterId())));
        assertThat(persistedType(fixture.firstParameterId()).getClassifier().getId())
                .isEqualTo(fixture.referencedClassifierId());

        assertAccepted(command(fixture.fixture(), "parameter.update", fixture.revision() + 3,
                Map.of("direction", "OUT"), target("parameter", fixture.firstParameterId())));
        assertThat(parameterRepository.findById(fixture.firstParameterId()).orElseThrow().getDirection()).isEqualTo("OUT");
        assertAccepted(command(fixture.fixture(), "parameter.update", fixture.revision() + 4,
                Map.of("direction", "inout"), target("parameter", fixture.firstParameterId())));
        assertThat(parameterRepository.findById(fixture.firstParameterId()).orElseThrow().getDirection()).isEqualTo("INOUT");

        UUID invalidDirection = UUID.randomUUID();
        assertRejected(command(fixture.fixture(), "parameter.update", fixture.revision() + 5,
                Map.of("direction", "RETURN"), target("parameter", fixture.firstParameterId())),
                ErrorCode.INVALID_PAYLOAD, "RETURN");
        assertRejected(command(fixture.fixture(), "parameter.update", fixture.revision() + 5,
                Map.of("type", Map.of("classifierId", invalidDirection)), target("parameter", fixture.firstParameterId())),
                ErrorCode.PRECONDITION_FAILED, "classifierId", invalidDirection.toString());
    }

    @Test
    void parameterUpdateMovesParametersAndKeepsOrdinalsDenseAndSiblingsIntact() {
        ParameterFixture fixture = parameterFixture();
        UUID lastParameterId = UUID.randomUUID();
        assertAccepted(command(fixture.fixture(), "parameter.add", fixture.revision(),
                Map.of("id", lastParameterId, "name", "last", "type", Map.of("primitiveName", "Boolean"),
                        "direction", "IN", "ordinal", 2), target("operation", fixture.operationId())));

        assertAccepted(command(fixture.fixture(), "parameter.update", fixture.revision() + 1,
                Map.of("ordinal", 0), target("parameter", lastParameterId)));
        List<OperationParameter> movedToFront = orderedParameters(fixture.operationId());
        assertThat(movedToFront).extracting(OperationParameter::getName).containsExactly("last", "first", "second");
        assertThat(movedToFront).extracting(OperationParameter::getOrdinal).containsExactly(0, 1, 2);
        assertThat(movedToFront).extracting(OperationParameter::getOrdinal).doesNotHaveDuplicates();
        assertThat(persistedType(fixture.firstParameterId()).getPrimitiveName()).isEqualTo("String");
        assertThat(persistedType(fixture.secondParameterId()).getPrimitiveName()).isEqualTo("Integer");

        assertAccepted(command(fixture.fixture(), "parameter.update", fixture.revision() + 2,
                Map.of("ordinal", 2), target("parameter", lastParameterId)));
        List<OperationParameter> movedBack = orderedParameters(fixture.operationId());
        assertThat(movedBack).extracting(OperationParameter::getName).containsExactly("first", "second", "last");
        assertThat(movedBack).extracting(OperationParameter::getOrdinal).containsExactly(0, 1, 2);
        assertThat(movedBack).extracting(OperationParameter::getOrdinal).doesNotHaveDuplicates();
        assertThat(persistedType(fixture.firstParameterId()).getPrimitiveName()).isEqualTo("String");
        assertThat(persistedType(fixture.secondParameterId()).getPrimitiveName()).isEqualTo("Integer");
    }

    @Test
    void parameterUpdateRejectsChangingTheOwningOperation() {
        ParameterFixture fixture = parameterFixture();
        UUID otherOperationId = UUID.randomUUID();
        CommandResult rejected = command(fixture.fixture(), "parameter.update", fixture.revision(),
                Map.of("operationId", otherOperationId), target("parameter", fixture.firstParameterId()));
        assertRejected(rejected, ErrorCode.PRECONDITION_FAILED, "owning operation", fixture.firstParameterId().toString());
        assertThat(parameterRepository.findById(fixture.firstParameterId()).orElseThrow().getOperation().getId())
                .isEqualTo(fixture.operationId());
    }

    @Test
    void parameterUpdateRejectsUnknownParameterIdByName() {
        Fixture fixture = fixture();
        UUID unknownParameterId = UUID.randomUUID();
        CommandResult rejected = command(fixture, "parameter.update", 0, Map.of("name", "missing"),
                target("parameter", unknownParameterId));
        assertRejected(rejected, ErrorCode.PRECONDITION_FAILED, unknownParameterId.toString());
    }

    private List<OperationParameter> orderedParameters(UUID operationId) {
        return parameterRepository.findByOperationId(operationId).stream()
                .sorted(java.util.Comparator.comparingInt(OperationParameter::getOrdinal))
                .toList();
    }

    private TypeReference persistedType(UUID parameterId) {
        UUID typeReferenceId = parameterRepository.findById(parameterId).orElseThrow().getTypeReference().getId();
        return typeReferenceRepository.findById(typeReferenceId).orElseThrow();
    }

    private ParameterFixture parameterFixture() {
        Fixture fixture = fixture();
        UUID ownerClassifierId = UUID.randomUUID();
        UUID referencedClassifierId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID firstParameterId = UUID.randomUUID();
        UUID secondParameterId = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0,
                Map.of("id", ownerClassifierId, "name", "Source", "kind", "CLASS"), List.of()));
        assertAccepted(command(fixture, "classifier.create", 1,
                Map.of("id", referencedClassifierId, "name", "Target", "kind", "CLASS"), List.of()));
        assertAccepted(command(fixture, "operation.add", 2, Map.of("id", operationId, "name", "execute"),
                target("classifier", ownerClassifierId)));
        assertAccepted(command(fixture, "parameter.add", 3,
                Map.of("id", firstParameterId, "name", "first", "type", Map.of("primitiveName", "String"),
                        "direction", "IN", "ordinal", 0), target("operation", operationId)));
        assertAccepted(command(fixture, "parameter.add", 4,
                Map.of("id", secondParameterId, "name", "second", "type", Map.of("primitiveName", "Integer"),
                        "direction", "OUT", "ordinal", 1), target("operation", operationId)));
        return new ParameterFixture(fixture, referencedClassifierId, operationId, firstParameterId, secondParameterId, 5);
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

    private record ParameterFixture(Fixture fixture, UUID referencedClassifierId, UUID operationId,
            UUID firstParameterId, UUID secondParameterId, long revision) {
    }

    private record TargetValue(String scope, UUID id) {
    }
}
