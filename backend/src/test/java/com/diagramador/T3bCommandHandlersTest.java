package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.command.Author;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandStatus;
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
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.Tombstone;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlConstraint;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UmlOperation;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.persistence.CascadeGroupRepository;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.ConnectorPathRepository;
import com.diagramador.persistence.DiagramViewRepository;
import com.diagramador.persistence.ElementGeometryRepository;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.TombstoneRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import com.diagramador.persistence.UserAccountRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class T3bCommandHandlersTest extends AbstractPostgresIntegrationTest {

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
    private final UmlRelationshipRepository relationshipRepository;
    private final RelationshipEndRepository relationshipEndRepository;
    private final ConnectorPathRepository connectorPathRepository;
    private final DiagramViewRepository viewRepository;
    private final ElementGeometryRepository geometryRepository;
    private final CascadeGroupRepository cascadeGroupRepository;
    private final TombstoneRepository tombstoneRepository;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    T3bCommandHandlersTest(CommandService commandService, UserAccountRepository userAccountRepository,
            ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository,
            UmlModelRepository modelRepository, ClassifierRepository classifierRepository,
            UmlAttributeRepository attributeRepository, UmlOperationRepository operationRepository,
            OperationParameterRepository parameterRepository, UmlConstraintRepository constraintRepository,
            UmlRelationshipRepository relationshipRepository, RelationshipEndRepository relationshipEndRepository,
            ConnectorPathRepository connectorPathRepository, DiagramViewRepository viewRepository,
            ElementGeometryRepository geometryRepository, CascadeGroupRepository cascadeGroupRepository,
            TombstoneRepository tombstoneRepository, TransactionTemplate transactionTemplate) {
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
        this.relationshipRepository = relationshipRepository;
        this.relationshipEndRepository = relationshipEndRepository;
        this.connectorPathRepository = connectorPathRepository;
        this.viewRepository = viewRepository;
        this.geometryRepository = geometryRepository;
        this.cascadeGroupRepository = cascadeGroupRepository;
        this.tombstoneRepository = tombstoneRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Test
    void classifierDeleteRemovesTheWholeGraphAndRecordsOneTombstonePerSupportedElement() {
        Fixture fixture = fixture("cascade");
        UUID deletedClassifier = UUID.randomUUID();
        UUID survivorClassifier = UUID.randomUUID();
        UUID attribute = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        UUID parameter = UUID.randomUUID();
        UUID constraint = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0, Map.of("id", deletedClassifier, "name", "Order",
                "kind", "CLASS"), List.of()));
        assertAccepted(command(fixture, "classifier.create", 1, Map.of("id", survivorClassifier, "name", "Customer",
                "kind", "CLASS"), List.of()));
        assertAccepted(command(fixture, "attribute.add", 2, Map.of("id", attribute, "name", "number",
                "type", Map.of("primitiveName", "String")), target("classifier", deletedClassifier)));
        assertAccepted(command(fixture, "operation.add", 3, Map.of("id", operation, "name", "place"),
                target("classifier", deletedClassifier)));
        assertAccepted(command(fixture, "parameter.add", 4, Map.of("id", parameter, "name", "customer",
                "type", Map.of("classifierId", survivorClassifier)), target("operation", operation)));
        assertAccepted(command(fixture, "constraint.add", 5, Map.of("id", constraint, "ownerType", "CLASSIFIER",
                "ownerId", deletedClassifier, "expression", "number != null"), target("classifier", deletedClassifier)));

        Graph graph = graph(fixture.model(), deletedClassifier, survivorClassifier, attribute, operation, parameter,
                constraint);
        assertThat(relationshipEndRepository.findByClassifierId(deletedClassifier)).hasSize(1);
        assertThat(constraintRepository.findByModelId(fixture.model().getId())).hasSize(1);
        int cascadeSize = 8;
        CommandResult result = command(fixture, "classifier.delete", 6, Map.of(),
                target("classifier", deletedClassifier), List.of(new Precondition("cascade_count", cascadeSize, null)));

        assertAccepted(result);
        assertThat(classifierRepository.findById(deletedClassifier)).isEmpty();
        assertThat(attributeRepository.findById(attribute)).isEmpty();
        assertThat(operationRepository.findById(operation)).isEmpty();
        assertThat(parameterRepository.findById(parameter)).isEmpty();
        assertThat(constraintRepository.findById(constraint)).isEmpty();
        assertThat(relationshipRepository.findById(graph.relationshipId())).isEmpty();
        assertThat(relationshipEndRepository.findAllById(graph.endIds())).isEmpty();
        assertThat(connectorPathRepository.findAllById(graph.pathIds())).isEmpty();
        assertThat(geometryRepository.findAllById(graph.geometryIds())).isEmpty();
        assertThat(classifierRepository.findById(survivorClassifier)).isPresent();
        assertThat(geometryRepository.findById(graph.survivorGeometryId())).isPresent();

        List<CascadeGroup> cascades = cascadeGroupRepository.findByModelId(fixture.model().getId());
        assertThat(cascades).hasSize(1);
        CascadeGroup cascade = cascades.get(0);
        List<Tombstone> tombstones = tombstoneRepository.findByCascadeCascadeId(cascade.getCascadeId());
        assertThat(cascadeSize).isEqualTo(8);
        assertThat(cascade.getElementCount()).isEqualTo(8);
        assertThat(tombstones).hasSize(8);
        assertThat(tombstones).hasSize(cascade.getElementCount());
        assertThat(tombstones.stream().map(tombstone -> new Pair(tombstone.getTargetType(), tombstone.getTargetId()))
                .distinct()).hasSize(8);
        assertThat(tombstones).allSatisfy(tombstone -> assertThat(tombstone.getCommand().getCommandId())
                .isEqualTo(result.operationId()));
        assertThat(tombstones).extracting(Tombstone::getTargetType)
                .containsExactlyInAnyOrder(TargetType.CLASSIFIER, TargetType.ATTRIBUTE, TargetType.OPERATION,
                        TargetType.PARAMETER, TargetType.CONSTRAINT, TargetType.RELATIONSHIP,
                        TargetType.RELATIONSHIP_END, TargetType.RELATIONSHIP_END);
    }

    @Test
    void classifierDeleteWithoutConfirmationLeavesTheModelAndRevisionUntouched() {
        Fixture fixture = fixture("missing-confirmation");
        UUID classifier = createClassifierWithAttribute(fixture);
        CommandResult result = command(fixture, "classifier.delete", 2, Map.of(), target("classifier", classifier));

        assertRejected(result, "2", "cascade_count");
        assertThat(classifierRepository.findById(classifier)).isPresent();
        assertThat(attributeRepository.findByClassifierId(classifier)).hasSize(1);
        assertThat(modelRepository.findById(fixture.model().getId()).orElseThrow().getCurrentRevision()).isEqualTo(2L);
        assertThat(cascadeGroupRepository.findByModelId(fixture.model().getId())).isEmpty();
        assertThat(tombstoneRepository.findByModelId(fixture.model().getId())).isEmpty();
    }

    @Test
    void classifierDeleteRejectsAConfirmationCountMismatchWithoutChangingTheModel() {
        Fixture fixture = fixture("wrong-confirmation");
        UUID classifier = createClassifierWithAttribute(fixture);
        CommandResult result = command(fixture, "classifier.delete", 2, Map.of(), target("classifier", classifier),
                List.of(new Precondition("cascade_count", 99, null)));

        assertRejected(result, "99", "2");
        assertThat(classifierRepository.findById(classifier)).isPresent();
        assertThat(attributeRepository.findByClassifierId(classifier)).hasSize(1);
        assertThat(modelRepository.findById(fixture.model().getId()).orElseThrow().getCurrentRevision()).isEqualTo(2L);
    }

    @Test
    void singleAttributeRemovalNeedsNoConfirmationAndWritesExactlyOneTombstone() {
        Fixture fixture = fixture("single-attribute");
        UUID classifier = createClassifier(fixture, 0);
        UUID attribute = UUID.randomUUID();
        assertAccepted(command(fixture, "attribute.add", 1, Map.of("id", attribute, "name", "name",
                "type", Map.of("primitiveName", "String")), target("classifier", classifier)));
        CommandResult result = command(fixture, "attribute.remove", 2, Map.of(), target("attribute", attribute));

        assertAccepted(result);
        assertThat(attributeRepository.findById(attribute)).isEmpty();
        List<CascadeGroup> cascades = cascadeGroupRepository.findByModelId(fixture.model().getId());
        assertThat(cascades).hasSize(1);
        CascadeGroup cascade = cascades.get(0);
        assertThat(cascade.getElementCount()).isEqualTo(1);
        assertThat(tombstoneRepository.findByCascadeCascadeId(cascade.getCascadeId())).singleElement()
                .satisfies(tombstone -> {
                    assertThat(tombstone.getTargetType()).isEqualTo(TargetType.ATTRIBUTE);
                    assertThat(tombstone.getTargetId()).isEqualTo(attribute);
                    assertThat(tombstone.getCommand().getCommandId()).isEqualTo(result.operationId());
                });
    }

    @Test
    void parameterRemovalStillWorksAndUsesTheUniformBookkeepingPath() {
        Fixture fixture = fixture("single-parameter");
        UUID classifier = createClassifier(fixture, 0);
        UUID operation = UUID.randomUUID();
        UUID parameter = UUID.randomUUID();
        assertAccepted(command(fixture, "operation.add", 1, Map.of("id", operation, "name", "save"),
                target("classifier", classifier)));
        assertAccepted(command(fixture, "parameter.add", 2, Map.of("id", parameter, "name", "request",
                "type", Map.of("primitiveName", "String")), target("operation", operation)));
        CommandResult result = command(fixture, "parameter.remove", 3, Map.of(), target("parameter", parameter));

        assertAccepted(result);
        assertThat(parameterRepository.findById(parameter)).isEmpty();
        List<CascadeGroup> cascades = cascadeGroupRepository.findByModelId(fixture.model().getId());
        assertThat(cascades).hasSize(1);
        CascadeGroup cascade = cascades.get(0);
        assertThat(cascade.getElementCount()).isEqualTo(1);
        assertThat(tombstoneRepository.findByCascadeCascadeId(cascade.getCascadeId())).singleElement()
                .extracting(Tombstone::getTargetType).isEqualTo(TargetType.PARAMETER);
    }

    private Graph graph(UmlModel model, UUID deletedClassifier, UUID survivorClassifier, UUID attribute,
            UUID operation, UUID parameter, UUID constraint) {
        return transactionTemplate.execute(status -> {
            UmlRelationship relationship = relationshipRepository.save(
                    UmlRelationship.create(model, RelationshipKind.ASSOCIATION, "places"));
            RelationshipEnd firstEnd = relationshipEndRepository.save(RelationshipEnd.create(relationship,
                    classifierRepository.findById(deletedClassifier).orElseThrow(), (short) 1, null,
                    MultiplicityKind.EXACTLY_ONE, NavigationKind.UNSPECIFIED, "NONE"));
            RelationshipEnd secondEnd = relationshipEndRepository.save(RelationshipEnd.create(relationship,
                    classifierRepository.findById(survivorClassifier).orElseThrow(), (short) 2, null,
                    MultiplicityKind.ZERO_OR_MANY, NavigationKind.UNSPECIFIED, "NONE"));
            DiagramView view = viewRepository.save(DiagramView.create(model, "Main", "CLASS", true));
            ConnectorPath path = connectorPathRepository.save(ConnectorPath.create(view, relationship, 0, 10, 20));
            ElementGeometry classifierGeometry = geometryRepository.save(
                    ElementGeometry.create(view, TargetType.CLASSIFIER, deletedClassifier, 1, 2, 3, 4, 0));
            ElementGeometry attributeGeometry = geometryRepository.save(
                    ElementGeometry.create(view, TargetType.ATTRIBUTE, attribute, 1, 2, 3, 4, 0));
            ElementGeometry operationGeometry = geometryRepository.save(
                    ElementGeometry.create(view, TargetType.OPERATION, operation, 1, 2, 3, 4, 0));
            ElementGeometry parameterGeometry = geometryRepository.save(
                    ElementGeometry.create(view, TargetType.PARAMETER, parameter, 1, 2, 3, 4, 0));
            ElementGeometry constraintGeometry = geometryRepository.save(
                    ElementGeometry.create(view, TargetType.CONSTRAINT, constraint, 1, 2, 3, 4, 0));
            ElementGeometry relationshipGeometry = geometryRepository.save(
                    ElementGeometry.create(view, TargetType.RELATIONSHIP, relationship.getId(), 1, 2, 3, 4, 0));
            ElementGeometry firstEndGeometry = geometryRepository.save(
                    ElementGeometry.create(view, TargetType.RELATIONSHIP_END, firstEnd.getId(), 1, 2, 3, 4, 0));
            ElementGeometry survivorGeometry = geometryRepository.save(
                    ElementGeometry.create(view, TargetType.CLASSIFIER, survivorClassifier, 1, 2, 3, 4, 0));
            return new Graph(relationship.getId(), Set.of(firstEnd.getId(), secondEnd.getId()), Set.of(path.getId()),
                    Set.of(classifierGeometry.getId(), attributeGeometry.getId(), operationGeometry.getId(),
                            parameterGeometry.getId(), constraintGeometry.getId(), relationshipGeometry.getId(),
                            firstEndGeometry.getId()), survivorGeometry.getId());
        });
    }

    private UUID createClassifierWithAttribute(Fixture fixture) {
        UUID classifier = createClassifier(fixture, 0);
        assertAccepted(command(fixture, "attribute.add", 1, Map.of("id", UUID.randomUUID(), "name", "name",
                "type", Map.of("primitiveName", "String")), target("classifier", classifier)));
        return classifier;
    }

    private UUID createClassifier(Fixture fixture, long revision) {
        UUID classifier = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", revision, Map.of("id", classifier, "name", "C" + revision,
                "kind", "CLASS"), List.of()));
        return classifier;
    }

    private CommandResult command(Fixture fixture, String type, long revision, Object payload, List<TargetValue> targets) {
        return command(fixture, type, revision, payload, targets, List.of());
    }

    private CommandResult command(Fixture fixture, String type, long revision, Object payload, List<TargetValue> targets,
            List<Precondition> preconditions) {
        return commandService.execute(new OperationEnvelope(UUID.randomUUID(), type, fixture.author(),
                fixture.project().getId(), fixture.model().getId(), revision,
                targets.stream().map(value -> new Target(value.scope(), value.id())).toList(), preconditions, payload,
                CommandOrigin.MANUAL, null, UUID.randomUUID().toString()));
    }

    private List<TargetValue> target(String scope, UUID id) {
        return List.of(new TargetValue(scope, id));
    }

    private void assertAccepted(CommandResult result) {
        assertThat(result.status()).withFailMessage("diagnostics: %s", result.diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);
    }

    private void assertRejected(CommandResult result, String... fragments) {
        assertThat(result.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(result.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code().name()).isEqualTo("PRECONDITION_FAILED");
            assertThat(diagnostic.message()).contains(fragments);
        });
    }

    private Fixture fixture(String label) {
        return transactionTemplate.execute(status -> {
            com.diagramador.domain.UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            com.diagramador.domain.UserAccount author = userAccountRepository.save(
                    com.diagramador.domain.UserAccount.create("t3b-" + UUID.randomUUID(), "T3b",
                            "t3b-" + UUID.randomUUID() + "@example.test"));
            Project project = projectRepository.save(Project.create(owner, "T3b " + label, "cascade tests"));
            UmlModel model = modelRepository.save(UmlModel.create(project, "T3b Model"));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            projectMemberRepository.save(ProjectMember.create(project, author, ProjectMemberRole.EDITOR));
            return new Fixture(project, model, new Author(author.getId(), "t3b"));
        });
    }

    private record Fixture(Project project, UmlModel model, Author author) {
    }

    private record TargetValue(String scope, UUID id) {
    }

    private record Pair(TargetType type, UUID id) {
    }

    private record Graph(UUID relationshipId, Set<UUID> endIds, Set<UUID> pathIds, Set<UUID> geometryIds,
            UUID survivorGeometryId) {
    }
}
