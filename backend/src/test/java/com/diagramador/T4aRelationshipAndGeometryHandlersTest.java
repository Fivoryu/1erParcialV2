package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.command.Author;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.ErrorCode;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.command.Precondition;
import com.diagramador.command.Target;
import com.diagramador.domain.CascadeGroup;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.ConnectorPath;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.ElementGeometry;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.Tombstone;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.persistence.CascadeGroupRepository;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.ConnectorPathRepository;
import com.diagramador.persistence.DiagramViewRepository;
import com.diagramador.persistence.ElementGeometryRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.TombstoneRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import com.diagramador.persistence.UserAccountRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.params.provider.Arguments;

@SpringBootTest
class T4aRelationshipAndGeometryHandlersTest extends AbstractPostgresIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final CommandService commandService;
    private final UserAccountRepository userAccountRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UmlModelRepository modelRepository;
    private final ClassifierRepository classifierRepository;
    private final UmlRelationshipRepository relationshipRepository;
    private final RelationshipEndRepository relationshipEndRepository;
    private final DiagramViewRepository viewRepository;
    private final ElementGeometryRepository geometryRepository;
    private final ConnectorPathRepository connectorPathRepository;
    private final CascadeGroupRepository cascadeGroupRepository;
    private final TombstoneRepository tombstoneRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    T4aRelationshipAndGeometryHandlersTest(CommandService commandService,
            UserAccountRepository userAccountRepository, ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository, UmlModelRepository modelRepository,
            ClassifierRepository classifierRepository, UmlRelationshipRepository relationshipRepository,
            RelationshipEndRepository relationshipEndRepository, DiagramViewRepository viewRepository,
            ElementGeometryRepository geometryRepository, ConnectorPathRepository connectorPathRepository,
            CascadeGroupRepository cascadeGroupRepository, TombstoneRepository tombstoneRepository,
            JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.commandService = commandService;
        this.userAccountRepository = userAccountRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.modelRepository = modelRepository;
        this.classifierRepository = classifierRepository;
        this.relationshipRepository = relationshipRepository;
        this.relationshipEndRepository = relationshipEndRepository;
        this.viewRepository = viewRepository;
        this.geometryRepository = geometryRepository;
        this.connectorPathRepository = connectorPathRepository;
        this.cascadeGroupRepository = cascadeGroupRepository;
        this.tombstoneRepository = tombstoneRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @ParameterizedTest(name = "{0} stores aggregation on source={1}, target={2}")
    @MethodSource("relationshipAggregationCases")
    void relationshipKindsPersistAggregationOnTheSpecifiedEnd(RelationshipKind kind, String sourceAggregation,
            String targetAggregation) {
        Fixture fixture = fixture("relationship-kind-" + kind);
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();

        assertAccepted(createRelationship(fixture, 2, relationshipId, kind.name(), classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null));

        UmlRelationship relationship = relationshipRepository.findById(relationshipId).orElseThrow();
        List<RelationshipEnd> ends = endsInOrdinalOrder(relationshipId);
        assertThat(relationship.getKind()).isEqualTo(kind);
        assertThat(ends).hasSize(2);
        assertThat(ends).extracting(RelationshipEnd::getOrdinal).containsExactly((short) 1, (short) 2);
        RelationshipEnd sourceEnd = ends.get(0);
        RelationshipEnd targetEnd = ends.get(1);
        assertThat(sourceEnd.getClassifier().getId()).isEqualTo(classifiers.source());
        assertThat(targetEnd.getClassifier().getId()).isEqualTo(classifiers.target());
        assertThat(sourceEnd.getAggregationKind()).isEqualTo(sourceAggregation);
        assertThat(targetEnd.getAggregationKind()).isEqualTo(targetAggregation);
    }

    static Stream<Arguments> relationshipAggregationCases() {
        return Stream.of(
                Arguments.of(RelationshipKind.ASSOCIATION, "NONE", "NONE"),
                Arguments.of(RelationshipKind.AGGREGATION, "SHARED", "NONE"),
                Arguments.of(RelationshipKind.COMPOSITION, "COMPOSITE", "NONE"),
                Arguments.of(RelationshipKind.GENERALIZATION, "NONE", "NONE"),
                Arguments.of(RelationshipKind.INTERFACE_REALIZATION, "NONE", "NONE"),
                Arguments.of(RelationshipKind.DEPENDENCY, "NONE", "NONE"));
    }

    @ParameterizedTest(name = "relationship end supports multiplicity {0}")
    @MethodSource("supportedMultiplicities")
    void relationshipEndsAcceptEachSupportedMultiplicity(MultiplicityKind multiplicity) {
        Fixture fixture = fixture("relationship-multiplicity-" + multiplicity);
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();

        assertAccepted(createRelationship(fixture, 2, relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of("multiplicity", multiplicity.name()),
                Map.of("multiplicity", multiplicity.name()), null));

        assertThat(endsInOrdinalOrder(relationshipId))
                .extracting(RelationshipEnd::getMultiplicity)
                .containsExactly(multiplicity, multiplicity);
    }

    static Stream<MultiplicityKind> supportedMultiplicities() {
        return Stream.of(MultiplicityKind.EXACTLY_ONE, MultiplicityKind.ZERO_OR_ONE,
                MultiplicityKind.ONE_OR_MANY, MultiplicityKind.ZERO_OR_MANY);
    }

    @Test
    void relationshipEndRoleAndNavigabilityAreStoredPerEnd() {
        Fixture fixture = fixture("relationship-end-properties");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();

        assertAccepted(createRelationship(fixture, 2, relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of("role", "manager", "navigability", "NAVIGABLE"),
                Map.of("role", "reports", "navigability", "NOT_NAVIGABLE"), null));

        List<RelationshipEnd> ends = endsInOrdinalOrder(relationshipId);
        assertThat(ends).extracting(RelationshipEnd::getRole).containsExactly("manager", "reports");
        assertThat(ends).extracting(RelationshipEnd::getNavigability)
                .containsExactly(com.diagramador.domain.NavigationKind.NAVIGABLE,
                        com.diagramador.domain.NavigationKind.NOT_NAVIGABLE);
    }

    @Test
    void selfRelationshipIsAcceptedWithTwoOrdinalEndsOnTheSameClassifier() {
        Fixture fixture = fixture("self-relationship");
        UUID classifierId = createClassifier(fixture, 0, "Employee");
        UUID relationshipId = UUID.randomUUID();

        assertAccepted(createRelationship(fixture, 1, relationshipId, "ASSOCIATION", classifierId, classifierId,
                Map.of(), Map.of(), null));

        List<RelationshipEnd> ends = endsInOrdinalOrder(relationshipId);
        assertThat(ends).hasSize(2);
        assertThat(ends).extracting(RelationshipEnd::getOrdinal).containsExactly((short) 1, (short) 2);
        assertThat(ends).extracting(end -> end.getClassifier().getId())
                .containsExactly(classifierId, classifierId);
    }

    @Test
    void relationshipRejectsRangeMultiplicityWithoutLowerBound() {
        Fixture fixture = fixture("relationship-range-multiplicity");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();

        CommandResult result = createRelationship(fixture, 2, relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of("multiplicity", "RANGE"), Map.of(), null);

        assertRejected(result, ErrorCode.INVALID_PAYLOAD, "lowerBound");
        assertThat(relationshipRepository.findById(relationshipId)).isEmpty();
        assertThat(currentRevision(fixture)).isEqualTo(2L);
    }

    @Test
    void relationshipEndsAcceptRangeWithBoundsAndUpdateThem() {
        Fixture fixture = fixture("relationship-range-bounds");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();

        assertAccepted(createRelationship(fixture, 2, relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of("multiplicity", "RANGE", "lowerBound", 2, "upperBound", 5),
                Map.of(), null));

        List<RelationshipEnd> ends = endsInOrdinalOrder(relationshipId);
        assertThat(ends.get(0).getMultiplicity()).isEqualTo(MultiplicityKind.RANGE);
        assertThat(ends.get(0).getLowerBound()).isEqualTo(2);
        assertThat(ends.get(0).getUpperBound()).isEqualTo(5);
        assertThat(ends.get(1).getLowerBound()).isNull();

        assertAccepted(command(fixture, "relationship.update", 3,
                Map.of("source", Map.of("multiplicity", "ZERO_OR_MANY")),
                target("relationship", relationshipId), List.of()));
        RelationshipEnd cleared = endsInOrdinalOrder(relationshipId).get(0);
        assertThat(cleared.getLowerBound()).isNull();
        assertThat(cleared.getUpperBound()).isNull();

        assertRejected(command(fixture, "relationship.update", 4,
                Map.of("target", Map.of("lowerBound", 1)),
                target("relationship", relationshipId), List.of()), ErrorCode.INVALID_PAYLOAD, "RANGE");
    }

    @Test
    void relationshipRejectsAggregationKindPayloadAsInvalid() {
        Fixture fixture = fixture("relationship-aggregation-payload");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> payload = relationshipPayload(relationshipId, "AGGREGATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);
        payload.put("aggregationKind", "COMPOSITE");

        CommandResult result = command(fixture, "relationship.create", 2, payload,
                classifierTargets(classifiers.source(), classifiers.target()), List.of());

        assertRejected(result, ErrorCode.INVALID_PAYLOAD, "aggregationKind");
        assertThat(relationshipRepository.findById(relationshipId)).isEmpty();
    }

    @Test
    void relationshipRejectsAnEndClassifierFromAnotherModelAndNamesIt() {
        Fixture fixture = fixture("cross-model-relationship");
        Fixture otherModel = fixture("other-relationship-model");
        UUID sourceId = createClassifier(fixture, 0, "Source");
        UUID foreignClassifierId = createClassifier(otherModel, 0, "Foreign");
        UUID relationshipId = UUID.randomUUID();

        CommandResult result = createRelationship(fixture, 1, relationshipId, "ASSOCIATION", sourceId,
                foreignClassifierId, Map.of(), Map.of(), null);

        assertRejected(result, ErrorCode.PRECONDITION_FAILED, foreignClassifierId.toString(),
                fixture.model().getId().toString());
        assertThat(relationshipRepository.findById(relationshipId)).isEmpty();
        assertThat(currentRevision(fixture)).isEqualTo(1L);
    }

    @Test
    void duplicateRelationshipIdIsRejectedAndNamed() {
        Fixture fixture = fixture("duplicate-relationship-id");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(createRelationship(fixture, 2, relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null));

        CommandResult duplicate = createRelationship(fixture, 3, relationshipId, "DEPENDENCY", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);

        assertRejected(duplicate, ErrorCode.PRECONDITION_FAILED, relationshipId.toString());
        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getKind())
                .isEqualTo(RelationshipKind.ASSOCIATION);
        assertThat(currentRevision(fixture)).isEqualTo(3L);
    }

    @Test
    void relationshipUpdateMovesAggregationMarkerToCompositionAndBack() {
        Fixture fixture = fixture("relationship-update-kind");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(createRelationship(fixture, 2, relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null));

        assertAccepted(command(fixture, "relationship.update", 3, Map.of("kind", "COMPOSITION"),
                target("relationship", relationshipId), List.of()));
        UmlRelationship composition = relationshipRepository.findById(relationshipId).orElseThrow();
        List<RelationshipEnd> compositionEnds = endsInOrdinalOrder(relationshipId);
        assertThat(composition.getKind()).isEqualTo(RelationshipKind.COMPOSITION);
        assertThat(compositionEnds.get(0).getAggregationKind()).isEqualTo("COMPOSITE");
        assertThat(compositionEnds.get(1).getAggregationKind()).isEqualTo("NONE");

        assertAccepted(command(fixture, "relationship.update", 4, Map.of("kind", "ASSOCIATION"),
                target("relationship", relationshipId), List.of()));
        UmlRelationship association = relationshipRepository.findById(relationshipId).orElseThrow();
        List<RelationshipEnd> associationEnds = endsInOrdinalOrder(relationshipId);
        assertThat(association.getKind()).isEqualTo(RelationshipKind.ASSOCIATION);
        assertThat(associationEnds.get(0).getAggregationKind()).isEqualTo("NONE");
        assertThat(associationEnds.get(1).getAggregationKind()).isEqualTo("NONE");
    }

    @Test
    void dependencyCreateStoresItsStereotypeAndIgnoresCase() {
        Fixture fixture = fixture("dependency-stereotype-create");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> payload = relationshipPayload(relationshipId, "DEPENDENCY", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);
        payload.put("stereotype", "use");

        assertAccepted(command(fixture, "relationship.create", 2, payload,
                classifierTargets(classifiers.source(), classifiers.target()), List.of()));

        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getStereotype()).isEqualTo("USE");
    }

    @Test
    void aRelationshipWithoutAStereotypeKeepsItNull() {
        Fixture fixture = fixture("dependency-without-stereotype");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();

        assertAccepted(createRelationship(fixture, 2, relationshipId, "DEPENDENCY", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null));

        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getStereotype()).isNull();
    }

    @Test
    void stereotypeIsRejectedOnAnythingButADependency() {
        Fixture fixture = fixture("stereotype-on-association");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> payload = relationshipPayload(relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);
        payload.put("stereotype", "USE");

        CommandResult result = command(fixture, "relationship.create", 2, payload,
                classifierTargets(classifiers.source(), classifiers.target()), List.of());

        assertRejected(result, ErrorCode.INVALID_PAYLOAD, "stereotype", "dependenc");
        assertThat(relationshipRepository.findById(relationshipId)).isEmpty();
    }

    @Test
    void anUnknownStereotypeIsRejectedAndNamed() {
        Fixture fixture = fixture("stereotype-unknown");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> payload = relationshipPayload(relationshipId, "DEPENDENCY", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);
        payload.put("stereotype", "TELEPORT");

        CommandResult result = command(fixture, "relationship.create", 2, payload,
                classifierTargets(classifiers.source(), classifiers.target()), List.of());

        assertRejected(result, ErrorCode.INVALID_PAYLOAD, "stereotype", "TELEPORT");
        assertThat(relationshipRepository.findById(relationshipId)).isEmpty();
    }

    @Test
    void relationshipUpdateSetsAndClearsTheStereotype() {
        Fixture fixture = fixture("stereotype-update");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(createRelationship(fixture, 2, relationshipId, "DEPENDENCY", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null));

        assertAccepted(command(fixture, "relationship.update", 3, Map.of("stereotype", "IMPORT"),
                target("relationship", relationshipId), List.of()));
        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getStereotype()).isEqualTo("IMPORT");

        Map<String, Object> clear = new java.util.HashMap<>();
        clear.put("stereotype", null);
        assertAccepted(command(fixture, "relationship.update", 4, clear,
                target("relationship", relationshipId), List.of()));
        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getStereotype()).isNull();
    }

    @Test
    void leavingDependencyDropsTheStereotypeAndTheUpdateOfOtherFieldsKeepsIt() {
        Fixture fixture = fixture("stereotype-kind-change");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> payload = relationshipPayload(relationshipId, "DEPENDENCY", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);
        payload.put("stereotype", "INCLUDE");
        assertAccepted(command(fixture, "relationship.create", 2, payload,
                classifierTargets(classifiers.source(), classifiers.target()), List.of()));

        assertAccepted(command(fixture, "relationship.update", 3, Map.of("name", "needs"),
                target("relationship", relationshipId), List.of()));
        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getStereotype()).isEqualTo("INCLUDE");

        assertAccepted(command(fixture, "relationship.update", 4, Map.of("kind", "ASSOCIATION"),
                target("relationship", relationshipId), List.of()));
        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getStereotype()).isNull();
    }

    @Test
    void anUpdateMayTurnARelationshipIntoAStereotypedDependencyInOneCommand() {
        Fixture fixture = fixture("stereotype-kind-and-value");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(createRelationship(fixture, 2, relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null));

        assertAccepted(command(fixture, "relationship.update", 3, Map.of("kind", "DEPENDENCY", "stereotype", "extend"),
                target("relationship", relationshipId), List.of()));

        UmlRelationship updated = relationshipRepository.findById(relationshipId).orElseThrow();
        assertThat(updated.getKind()).isEqualTo(RelationshipKind.DEPENDENCY);
        assertThat(updated.getStereotype()).isEqualTo("EXTEND");
    }

    @Test
    void anUpdateThatLeavesDependencyWhileNamingAStereotypeIsRejectedAndChangesNothing() {
        Fixture fixture = fixture("stereotype-leaving-with-value");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> payload = relationshipPayload(relationshipId, "DEPENDENCY", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);
        payload.put("stereotype", "USE");
        assertAccepted(command(fixture, "relationship.create", 2, payload,
                classifierTargets(classifiers.source(), classifiers.target()), List.of()));

        CommandResult result = command(fixture, "relationship.update", 3,
                Map.of("kind", "ASSOCIATION", "stereotype", "USE"), target("relationship", relationshipId), List.of());

        assertRejected(result, ErrorCode.INVALID_PAYLOAD, "stereotype", "dependenc");
        UmlRelationship unchanged = relationshipRepository.findById(relationshipId).orElseThrow();
        assertThat(unchanged.getKind()).isEqualTo(RelationshipKind.DEPENDENCY);
        assertThat(unchanged.getStereotype()).isEqualTo("USE");
    }

    @Test
    void anAssociationMayNameAClassThatIsItsAssociationClass() {
        Fixture fixture = fixture("association-class-create");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID membership = createClassifier(fixture, 2, "Membership");
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> payload = relationshipPayload(relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);
        payload.put("associationClassId", membership);

        assertAccepted(command(fixture, "relationship.create", 3, payload,
                classifierTargets(classifiers.source(), classifiers.target()), List.of()));

        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getAssociationClassId())
                .isEqualTo(membership);
    }

    @Test
    void aRelationshipWithoutAnAssociationClassKeepsItNull() {
        Fixture fixture = fixture("association-class-absent");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();

        assertAccepted(createRelationship(fixture, 2, relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null));

        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getAssociationClassId()).isNull();
    }

    @Test
    void anAssociationClassCannotBeOneOfTheEndsOfItsOwnAssociation() {
        Fixture fixture = fixture("association-class-is-an-end");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> payload = relationshipPayload(relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);
        payload.put("associationClassId", classifiers.source());

        CommandResult result = command(fixture, "relationship.create", 2, payload,
                classifierTargets(classifiers.source(), classifiers.target()), List.of());

        assertRejected(result, ErrorCode.INVALID_PAYLOAD, "associationClassId", "ends");
        assertThat(relationshipRepository.findById(relationshipId)).isEmpty();
    }

    @Test
    void onlyAnAssociationLikeRelationshipHasAnAssociationClass() {
        Fixture fixture = fixture("association-class-on-dependency");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID membership = createClassifier(fixture, 2, "Membership");
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> payload = relationshipPayload(relationshipId, "DEPENDENCY", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);
        payload.put("associationClassId", membership);

        CommandResult result = command(fixture, "relationship.create", 3, payload,
                classifierTargets(classifiers.source(), classifiers.target()), List.of());

        assertRejected(result, ErrorCode.INVALID_PAYLOAD, "associationClassId", "association");
        assertThat(relationshipRepository.findById(relationshipId)).isEmpty();
    }

    @Test
    void anInterfaceCannotBeAnAssociationClass() {
        Fixture fixture = fixture("association-class-interface");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID contract = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 2,
                Map.of("id", contract, "name", "Contract", "kind", "INTERFACE"), List.of(), List.of()));
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> payload = relationshipPayload(relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);
        payload.put("associationClassId", contract);

        CommandResult result = command(fixture, "relationship.create", 3, payload,
                classifierTargets(classifiers.source(), classifiers.target()), List.of());

        assertRejected(result, ErrorCode.INVALID_PAYLOAD, "associationClassId", "class");
        assertThat(relationshipRepository.findById(relationshipId)).isEmpty();
    }

    @Test
    void aClassIsTheAssociationClassOfOneRelationshipOnlyAndTheErrorNamesTheOther() {
        Fixture fixture = fixture("association-class-twice");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID membership = createClassifier(fixture, 2, "Membership");
        UUID first = UUID.randomUUID();
        Map<String, Object> firstPayload = relationshipPayload(first, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);
        firstPayload.put("associationClassId", membership);
        assertAccepted(command(fixture, "relationship.create", 3, firstPayload,
                classifierTargets(classifiers.source(), classifiers.target()), List.of()));
        UUID second = UUID.randomUUID();
        Map<String, Object> secondPayload = relationshipPayload(second, "AGGREGATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);
        secondPayload.put("associationClassId", membership);

        CommandResult result = command(fixture, "relationship.create", 4, secondPayload,
                classifierTargets(classifiers.source(), classifiers.target()), List.of());

        assertRejected(result, ErrorCode.PRECONDITION_FAILED, first.toString());
        assertThat(relationshipRepository.findById(second)).isEmpty();
        assertThat(relationshipRepository.findById(first).orElseThrow().getAssociationClassId()).isEqualTo(membership);
    }

    @Test
    void anAssociationClassFromAnotherModelIsRejectedAndNamed() {
        Fixture fixture = fixture("association-class-foreign");
        Fixture otherModel = fixture("association-class-foreign-other");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID foreign = createClassifier(otherModel, 0, "Foreign");
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> payload = relationshipPayload(relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);
        payload.put("associationClassId", foreign);

        CommandResult result = command(fixture, "relationship.create", 2, payload,
                classifierTargets(classifiers.source(), classifiers.target()), List.of());

        assertRejected(result, ErrorCode.PRECONDITION_FAILED, foreign.toString(), fixture.model().getId().toString());
        assertThat(relationshipRepository.findById(relationshipId)).isEmpty();
    }

    @Test
    void relationshipUpdateSetsKeepsAndClearsTheAssociationClass() {
        Fixture fixture = fixture("association-class-update");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID membership = createClassifier(fixture, 2, "Membership");
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(createRelationship(fixture, 3, relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null));

        assertAccepted(command(fixture, "relationship.update", 4, Map.of("associationClassId", membership),
                target("relationship", relationshipId), List.of()));
        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getAssociationClassId())
                .isEqualTo(membership);

        assertAccepted(command(fixture, "relationship.update", 5, Map.of("name", "enrols"),
                target("relationship", relationshipId), List.of()));
        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getAssociationClassId())
                .isEqualTo(membership);

        Map<String, Object> clear = new java.util.HashMap<>();
        clear.put("associationClassId", null);
        assertAccepted(command(fixture, "relationship.update", 6, clear,
                target("relationship", relationshipId), List.of()));
        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getAssociationClassId()).isNull();
    }

    @Test
    void leavingTheAssociationKindsDropsTheAssociationClass() {
        Fixture fixture = fixture("association-class-kind-change");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID membership = createClassifier(fixture, 2, "Membership");
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> payload = relationshipPayload(relationshipId, "COMPOSITION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);
        payload.put("associationClassId", membership);
        assertAccepted(command(fixture, "relationship.create", 3, payload,
                classifierTargets(classifiers.source(), classifiers.target()), List.of()));

        assertAccepted(command(fixture, "relationship.update", 4, Map.of("kind", "AGGREGATION"),
                target("relationship", relationshipId), List.of()));
        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getAssociationClassId())
                .isEqualTo(membership);

        assertAccepted(command(fixture, "relationship.update", 5, Map.of("kind", "DEPENDENCY"),
                target("relationship", relationshipId), List.of()));
        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getAssociationClassId()).isNull();
    }

    @Test
    void deletingTheAssociationClassKeepsTheRelationshipAndReleasesTheLink() {
        Fixture fixture = fixture("association-class-deleted");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID membership = createClassifier(fixture, 2, "Membership");
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> payload = relationshipPayload(relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);
        payload.put("associationClassId", membership);
        assertAccepted(command(fixture, "relationship.create", 3, payload,
                classifierTargets(classifiers.source(), classifiers.target()), List.of()));

        assertAccepted(command(fixture, "classifier.delete", 4, Map.of(), target("classifier", membership),
                List.of(new Precondition("cascade_count", 1, null))));

        assertThat(classifierRepository.findById(membership)).isEmpty();
        UmlRelationship survivor = relationshipRepository.findById(relationshipId).orElseThrow();
        assertThat(survivor.getAssociationClassId()).isNull();
    }

    @Test
    void deletingTheRelationshipKeepsTheAssociationClassAsAnOrdinaryClass() {
        Fixture fixture = fixture("association-class-relationship-deleted");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID membership = createClassifier(fixture, 2, "Membership");
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> payload = relationshipPayload(relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null);
        payload.put("associationClassId", membership);
        assertAccepted(command(fixture, "relationship.create", 3, payload,
                classifierTargets(classifiers.source(), classifiers.target()), List.of()));

        assertAccepted(command(fixture, "relationship.delete", 4, Map.of(), target("relationship", relationshipId),
                List.of(new Precondition("cascade_count", 3, null))));

        assertThat(relationshipRepository.findById(relationshipId)).isEmpty();
        assertThat(classifierRepository.findById(membership)).isPresent();
    }

    @Test
    void relationshipUpdateRejectsAStereotypeOnANonDependency() {
        Fixture fixture = fixture("stereotype-update-association");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(createRelationship(fixture, 2, relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null));

        CommandResult result = command(fixture, "relationship.update", 3, Map.of("stereotype", "USE"),
                target("relationship", relationshipId), List.of());

        assertRejected(result, ErrorCode.INVALID_PAYLOAD, "stereotype", "dependenc");
        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getStereotype()).isNull();
    }

    @Test
    void relationshipCreatePersistsWaypointsAndDeleteWritesTombstonesForRelationshipAndEnds() {
        Fixture fixture = fixture("relationship-delete-tombstones");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(createRelationship(fixture, 2, relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), List.of(Map.of("x", 10, "y", 20))));
        List<RelationshipEnd> ends = endsInOrdinalOrder(relationshipId);
        assertThat(paths(fixture.view().getId(), relationshipId)).hasSize(1);

        CommandResult deleted = command(fixture, "relationship.delete", 3, Map.of(),
                target("relationship", relationshipId), List.of(new Precondition("cascade_count", 3, null)));

        assertAccepted(deleted);
        assertThat(relationshipRepository.findById(relationshipId)).isEmpty();
        assertThat(relationshipEndRepository.findByRelationshipId(relationshipId)).isEmpty();
        assertThat(paths(fixture.view().getId(), relationshipId)).isEmpty();
        List<CascadeGroup> cascades = cascadeGroupRepository.findByModelId(fixture.model().getId());
        assertThat(cascades).hasSize(1);
        List<Tombstone> tombstones = tombstoneRepository.findByCascadeCascadeId(cascades.get(0).getCascadeId());
        assertThat(tombstones).hasSize(3);
        assertThat(tombstones).extracting(Tombstone::getTargetType)
                .containsExactlyInAnyOrder(TargetType.RELATIONSHIP, TargetType.RELATIONSHIP_END,
                        TargetType.RELATIONSHIP_END);
        assertThat(tombstones).extracting(Tombstone::getTargetId)
                .containsExactlyInAnyOrder(relationshipId, ends.get(0).getId(), ends.get(1).getId());
        assertThat(tombstones).allSatisfy(tombstone ->
                assertThat(tombstone.getCommand().getCommandId()).isEqualTo(deleted.operationId()));
    }

    @Test
    void refusedRelationshipDeleteCascadeLeavesModelAndRevisionUntouched() {
        Fixture fixture = fixture("relationship-delete-refused-cascade");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(createRelationship(fixture, 2, relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null));
        List<RelationshipEnd> endsBefore = endsInOrdinalOrder(relationshipId);

        CommandResult rejected = command(fixture, "relationship.delete", 3, Map.of(),
                target("relationship", relationshipId), List.of(new Precondition("cascade_count", 2, null)));

        assertRejected(rejected, ErrorCode.PRECONDITION_FAILED, "cascade_count", "2", "3");
        assertThat(relationshipRepository.findById(relationshipId)).isPresent();
        assertThat(endsInOrdinalOrder(relationshipId)).extracting(RelationshipEnd::getId)
                .containsExactly(endsBefore.get(0).getId(), endsBefore.get(1).getId());
        assertThat(currentRevision(fixture)).isEqualTo(3L);
        assertThat(cascadeGroupRepository.findByModelId(fixture.model().getId())).isEmpty();
        assertThat(tombstoneRepository.findByModelId(fixture.model().getId())).isEmpty();
    }

    @Test
    void geometrySetUpsertsOneClassifierRectangleWithTheSecondPosition() {
        Fixture fixture = fixture("geometry-upsert");
        UUID classifierId = createClassifier(fixture, 0, "Node");
        assertAccepted(setClassifierGeometry(fixture, 1, classifierId, 10, 20, 100, 60, 1));

        assertAccepted(setClassifierGeometry(fixture, 2, classifierId, 30, 40, 120, 80, 2));

        List<ElementGeometry> geometries = geometries(fixture.view().getId(), TargetType.CLASSIFIER, classifierId);
        assertThat(geometries).hasSize(1);
        assertThat(geometries.get(0)).satisfies(geometry -> {
            assertThat(geometry.getX()).isEqualTo(30);
            assertThat(geometry.getY()).isEqualTo(40);
            assertThat(geometry.getW()).isEqualTo(120);
            assertThat(geometry.getH()).isEqualTo(80);
            assertThat(geometry.getZOrder()).isEqualTo(2);
        });
    }

    @Test
    void geometrySetReplacesRelationshipWaypointsForTheViewWithZeroBasedOrdinals() {
        Fixture fixture = fixture("geometry-replace-waypoints");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(createRelationship(fixture, 2, relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null));
        assertAccepted(setRelationshipWaypoints(fixture, 3, relationshipId,
                List.of(Map.of("x", 1, "y", 2), Map.of("x", 3, "y", 4), Map.of("x", 5, "y", 6))));

        assertAccepted(setRelationshipWaypoints(fixture, 4, relationshipId,
                List.of(Map.of("x", 10, "y", 20), Map.of("x", 30, "y", 40))));

        List<ConnectorPath> paths = paths(fixture.view().getId(), relationshipId);
        assertThat(paths).hasSize(2);
        assertThat(paths).extracting(ConnectorPath::getOrdinal).containsExactly(0, 1);
        assertThat(paths).extracting(ConnectorPath::getX).containsExactly(10, 30);
        assertThat(paths).extracting(ConnectorPath::getY).containsExactly(20, 40);
    }

    @Test
    void geometrySetWithAnEmptyWaypointListRemovesExistingPaths() {
        Fixture fixture = fixture("geometry-empty-waypoints");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(createRelationship(fixture, 2, relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null));
        assertAccepted(setRelationshipWaypoints(fixture, 3, relationshipId,
                List.of(Map.of("x", 1, "y", 2), Map.of("x", 3, "y", 4))));
        assertThat(paths(fixture.view().getId(), relationshipId)).hasSize(2);

        assertAccepted(setRelationshipWaypoints(fixture, 4, relationshipId, List.of()));

        assertThat(paths(fixture.view().getId(), relationshipId)).isEmpty();
    }

    @Test
    void geometryRemoveRemovesTheClassifierRectangle() {
        Fixture fixture = fixture("geometry-remove-rectangle");
        UUID classifierId = createClassifier(fixture, 0, "Node");
        assertAccepted(setClassifierGeometry(fixture, 1, classifierId, 10, 20, 100, 60, 0));

        assertAccepted(removeGeometry(fixture, 2, "CLASSIFIER", fixture.view().getId(), classifierId));

        assertThat(geometries(fixture.view().getId(), TargetType.CLASSIFIER, classifierId)).isEmpty();
    }

    @Test
    void geometryRemoveSeparatelyRemovesRelationshipWaypoints() {
        Fixture fixture = fixture("geometry-remove-waypoints");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(createRelationship(fixture, 2, relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null));
        assertAccepted(setRelationshipWaypoints(fixture, 3, relationshipId,
                List.of(Map.of("x", 10, "y", 20), Map.of("x", 30, "y", 40))));

        assertAccepted(removeGeometry(fixture, 4, "RELATIONSHIP", fixture.view().getId(), relationshipId));

        assertThat(paths(fixture.view().getId(), relationshipId)).isEmpty();
    }

    @ParameterizedTest(name = "geometry commands reject targetType {0}")
    @MethodSource("unsupportedGeometryTargets")
    void geometrySetAndRemoveRejectEveryNonLayoutTargetType(String targetType) {
        Fixture fixture = fixture("unsupported-geometry-target-" + targetType);
        UUID targetId = UUID.randomUUID();
        Map<String, Object> commonPayload = Map.of("targetType", targetType, "viewId", fixture.view().getId(),
                "targetId", targetId);

        CommandResult set = command(fixture, "geometry.set", 0, commonPayload, List.of(), List.of());
        CommandResult remove = command(fixture, "geometry.remove", 0, commonPayload, List.of(), List.of());

        assertRejected(set, ErrorCode.INVALID_PAYLOAD, targetType);
        assertRejected(remove, ErrorCode.INVALID_PAYLOAD, targetType);
        assertThat(currentRevision(fixture)).isZero();
    }

    static Stream<String> unsupportedGeometryTargets() {
        return Stream.of("ATTRIBUTE", "OPERATION", "PARAMETER", "CONSTRAINT", "RELATIONSHIP_END", "DIAGRAM_VIEW");
    }

    @ParameterizedTest(name = "geometry rejects non-positive {0}")
    @MethodSource("invalidGeometryDimensions")
    void geometrySetRejectsNonPositiveWidthOrHeightWithSchemaConstraint(String dimension, int invalidValue) {
        Fixture fixture = fixture("invalid-geometry-" + dimension + "-" + invalidValue);
        UUID classifierId = createClassifier(fixture, 0, "Node");
        Map<String, Object> payload = Map.of("targetType", "CLASSIFIER", "viewId", fixture.view().getId(),
                "targetId", classifierId, "x", 1, "y", 2, "w", dimension.equals("w") ? invalidValue : 10,
                "h", dimension.equals("h") ? invalidValue : 20);

        CommandResult result = command(fixture, "geometry.set", 1, payload, List.of(), List.of());

        assertRejected(result, ErrorCode.INVALID_PAYLOAD, "chk_geometry_dims");
        assertThat(geometries(fixture.view().getId(), TargetType.CLASSIFIER, classifierId)).isEmpty();
        assertThat(currentRevision(fixture)).isEqualTo(1L);
    }

    static Stream<Arguments> invalidGeometryDimensions() {
        return Stream.of(Arguments.of("w", 0), Arguments.of("h", -1));
    }

    @Test
    void geometryRejectsAViewFromAnotherModelAndNamesBoth() {
        Fixture fixture = fixture("geometry-cross-model");
        Fixture otherModel = fixture("geometry-other-model");
        UUID classifierId = createClassifier(fixture, 0, "Node");

        CommandResult result = command(fixture, "geometry.set", 1,
                Map.of("targetType", "CLASSIFIER", "viewId", otherModel.view().getId(), "targetId", classifierId,
                        "x", 1, "y", 2, "w", 10, "h", 20), List.of(), List.of());

        assertRejected(result, ErrorCode.PRECONDITION_FAILED, otherModel.view().getId().toString(),
                fixture.model().getId().toString());
        assertThat(currentRevision(fixture)).isEqualTo(1L);
    }

    @Test
    void geometryRejectsMissingTargetsAndMismatchedTargetTypesNamingTheTarget() {
        Fixture fixture = fixture("geometry-target-integrity");
        ClassifierIds classifiers = createClassifiers(fixture);
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(createRelationship(fixture, 2, relationshipId, "ASSOCIATION", classifiers.source(),
                classifiers.target(), Map.of(), Map.of(), null));
        UUID missingId = UUID.randomUUID();

        CommandResult missing = command(fixture, "geometry.set", 3,
                classifierGeometryPayload(fixture.view().getId(), missingId), List.of(), List.of());
        CommandResult mismatch = command(fixture, "geometry.set", 3,
                relationshipGeometryPayload(fixture.view().getId(), classifiers.source(), List.of()), List.of(), List.of());

        assertRejected(missing, ErrorCode.PRECONDITION_FAILED, missingId.toString());
        assertRejected(mismatch, ErrorCode.PRECONDITION_FAILED, classifiers.source().toString());
        assertThat(currentRevision(fixture)).isEqualTo(3L);
    }

    @Test
    void geometrySetChangesOnlyLayoutRowsAndAdvancesTheModelRevision() {
        Fixture fixture = fixture("geometry-semantic-separation");
        UUID sourceId = createClassifier(fixture, 0, "Order");
        UUID targetId = createClassifier(fixture, 1, "Customer");
        UUID operationId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();
        UUID constraintId = UUID.randomUUID();
        assertAccepted(command(fixture, "attribute.add", 2,
                Map.of("id", UUID.randomUUID(), "name", "number", "type", Map.of("primitiveName", "String")),
                target("classifier", sourceId), List.of()));
        assertAccepted(command(fixture, "operation.add", 3,
                Map.of("id", operationId, "name", "place"), target("classifier", sourceId), List.of()));
        assertAccepted(command(fixture, "parameter.add", 4,
                Map.of("id", UUID.randomUUID(), "name", "customer", "type", Map.of("classifierId", targetId)),
                target("operation", operationId), List.of()));
        assertAccepted(createRelationship(fixture, 5, relationshipId, "ASSOCIATION", sourceId, targetId,
                Map.of(), Map.of(), null));
        assertAccepted(command(fixture, "constraint.add", 6,
                Map.of("id", constraintId, "ownerType", "CLASSIFIER", "ownerId", sourceId,
                        "expression", "number != null"), target("classifier", sourceId), List.of()));
        SemanticRows before = semanticRows(fixture);
        long revisionBefore = currentRevision(fixture);

        CommandResult result = setClassifierGeometry(fixture, revisionBefore, sourceId, 40, 50, 160, 90, 3);

        assertAccepted(result);
        assertThat(semanticRows(fixture)).isEqualTo(before);
        assertThat(currentRevision(fixture)).isEqualTo(revisionBefore + 1);
        assertThat(geometries(fixture.view().getId(), TargetType.CLASSIFIER, sourceId))
                .singleElement().satisfies(geometry -> {
                    assertThat(geometry.getX()).isEqualTo(40);
                    assertThat(geometry.getY()).isEqualTo(50);
                });
    }

    private CommandResult createRelationship(Fixture fixture, long revision, UUID relationshipId, String kind,
            UUID sourceId, UUID targetId, Map<String, Object> sourceProperties, Map<String, Object> targetProperties,
            List<Map<String, Integer>> waypoints) {
        Map<String, Object> payload = relationshipPayload(relationshipId, kind, sourceId, targetId,
                sourceProperties, targetProperties, waypoints);
        return command(fixture, "relationship.create", revision, payload,
                classifierTargets(sourceId, targetId), List.of());
    }

    private Map<String, Object> relationshipPayload(UUID relationshipId, String kind, UUID sourceId, UUID targetId,
            Map<String, Object> sourceProperties, Map<String, Object> targetProperties,
            List<Map<String, Integer>> waypoints) {
        Map<String, Object> source = new java.util.HashMap<>(sourceProperties);
        source.put("classifierId", sourceId);
        Map<String, Object> target = new java.util.HashMap<>(targetProperties);
        target.put("classifierId", targetId);
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("id", relationshipId);
        payload.put("kind", kind);
        payload.put("source", source);
        payload.put("target", target);
        if (waypoints != null) {
            payload.put("waypoints", waypoints);
        }
        return payload;
    }

    private CommandResult setClassifierGeometry(Fixture fixture, long revision, UUID classifierId, int x, int y,
            int w, int h, int zOrder) {
        return command(fixture, "geometry.set", revision,
                Map.of("targetType", "CLASSIFIER", "viewId", fixture.view().getId(), "targetId", classifierId,
                        "x", x, "y", y, "w", w, "h", h, "zOrder", zOrder), List.of(), List.of());
    }

    private CommandResult setRelationshipWaypoints(Fixture fixture, long revision, UUID relationshipId,
            List<Map<String, Integer>> waypoints) {
        return command(fixture, "geometry.set", revision,
                Map.of("targetType", "RELATIONSHIP", "viewId", fixture.view().getId(), "targetId", relationshipId,
                        "waypoints", waypoints), List.of(), List.of());
    }

    private CommandResult removeGeometry(Fixture fixture, long revision, String targetType, UUID viewId,
            UUID targetId) {
        return command(fixture, "geometry.remove", revision,
                Map.of("targetType", targetType, "viewId", viewId, "targetId", targetId), List.of(), List.of());
    }

    private Map<String, Object> classifierGeometryPayload(UUID viewId, UUID classifierId) {
        return Map.of("targetType", "CLASSIFIER", "viewId", viewId, "targetId", classifierId,
                "x", 1, "y", 2, "w", 10, "h", 20);
    }

    private Map<String, Object> relationshipGeometryPayload(UUID viewId, UUID relationshipId,
            List<Map<String, Integer>> waypoints) {
        return Map.of("targetType", "RELATIONSHIP", "viewId", viewId, "targetId", relationshipId,
                "waypoints", waypoints);
    }

    private List<Target> classifierTargets(UUID sourceId, UUID targetId) {
        return List.of(new Target("classifier", sourceId), new Target("classifier", targetId));
    }

    private List<Target> target(String scope, UUID id) {
        return List.of(new Target(scope, id));
    }

    private CommandResult command(Fixture fixture, String type, long revision, Object payload, List<Target> targets,
            List<Precondition> preconditions) {
        return commandService.execute(new OperationEnvelope(UUID.randomUUID(), type, fixture.author(),
                fixture.project().getId(), fixture.model().getId(), revision, targets, preconditions, payload,
                CommandOrigin.MANUAL, null, UUID.randomUUID().toString()));
    }

    private List<RelationshipEnd> endsInOrdinalOrder(UUID relationshipId) {
        return relationshipEndRepository.findByRelationshipId(relationshipId).stream()
                .sorted(Comparator.comparingInt(RelationshipEnd::getOrdinal)).toList();
    }

    private List<ElementGeometry> geometries(UUID viewId, TargetType targetType, UUID targetId) {
        return geometryRepository.findByViewId(viewId).stream()
                .filter(geometry -> geometry.getTargetType() == targetType && geometry.getTargetId().equals(targetId))
                .toList();
    }

    private List<ConnectorPath> paths(UUID viewId, UUID relationshipId) {
        return connectorPathRepository.findByRelationshipId(relationshipId).stream()
                .filter(path -> path.getView().getId().equals(viewId))
                .sorted(Comparator.comparingInt(ConnectorPath::getOrdinal)).toList();
    }

    private SemanticRows semanticRows(Fixture fixture) {
        UUID modelId = fixture.model().getId();
        return new SemanticRows(
                jdbcTemplate.queryForList("SELECT c.* FROM diagrammer.classifier c WHERE c.model_id = ? ORDER BY c.classifier_id",
                        modelId),
                jdbcTemplate.queryForList("SELECT a.* FROM diagrammer.uml_attribute a "
                        + "JOIN diagrammer.classifier c ON c.classifier_id = a.classifier_id "
                        + "WHERE c.model_id = ? ORDER BY a.attribute_id", modelId),
                jdbcTemplate.queryForList("SELECT o.* FROM diagrammer.uml_operation o "
                        + "JOIN diagrammer.classifier c ON c.classifier_id = o.classifier_id "
                        + "WHERE c.model_id = ? ORDER BY o.operation_id", modelId),
                jdbcTemplate.queryForList("SELECT p.* FROM diagrammer.operation_parameter p "
                        + "JOIN diagrammer.uml_operation o ON o.operation_id = p.operation_id "
                        + "JOIN diagrammer.classifier c ON c.classifier_id = o.classifier_id "
                        + "WHERE c.model_id = ? ORDER BY p.parameter_id", modelId),
                jdbcTemplate.queryForList("SELECT r.* FROM diagrammer.uml_relationship r "
                        + "WHERE r.model_id = ? ORDER BY r.relationship_id", modelId),
                jdbcTemplate.queryForList("SELECT c.* FROM diagrammer.uml_constraint c "
                        + "WHERE c.model_id = ? ORDER BY c.constraint_id", modelId));
    }

    private long currentRevision(Fixture fixture) {
        return modelRepository.findById(fixture.model().getId()).orElseThrow().getCurrentRevision();
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

    private ClassifierIds createClassifiers(Fixture fixture) {
        return new ClassifierIds(createClassifier(fixture, 0, "Source"),
                createClassifier(fixture, 1, "Target"));
    }

    private UUID createClassifier(Fixture fixture, long revision, String name) {
        UUID id = UUID.randomUUID();
        CommandResult result = command(fixture, "classifier.create", revision,
                Map.of("id", id, "name", name, "kind", "CLASS"), List.of(), List.of());
        assertAccepted(result);
        return id;
    }

    private Fixture fixture(String label) {
        return transactionTemplate.execute(status -> {
            com.diagramador.domain.UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            com.diagramador.domain.UserAccount author = userAccountRepository.save(
                    com.diagramador.domain.UserAccount.create("t4a-" + UUID.randomUUID(), "T4a",
                            "t4a-" + UUID.randomUUID() + "@example.test"));
            Project project = projectRepository.save(Project.create(owner, "T4a " + label, "relationship and geometry tests"));
            UmlModel model = modelRepository.save(UmlModel.create(project, "T4a Model"));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            projectMemberRepository.save(ProjectMember.create(project, author, ProjectMemberRole.EDITOR));
            DiagramView view = viewRepository.save(DiagramView.create(model, "Main", "CLASS", true));
            return new Fixture(project, model, new Author(author.getId(), "t4a"), view);
        });
    }

    private record Fixture(Project project, UmlModel model, Author author, DiagramView view) {
    }

    private record ClassifierIds(UUID source, UUID target) {
    }

    private record SemanticRows(List<Map<String, Object>> classifiers, List<Map<String, Object>> attributes,
            List<Map<String, Object>> operations, List<Map<String, Object>> parameters,
            List<Map<String, Object>> relationships, List<Map<String, Object>> constraints) {
    }
}
