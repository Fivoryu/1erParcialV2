package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramador.command.Author;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandService;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.command.Target;
import com.diagramador.command.ValidationState;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.ModelVersion;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.generation.InputSnapshot;
import com.diagramador.generation.InputSnapshotFactory;
import com.diagramador.persistence.DiagramViewRepository;
import com.diagramador.persistence.ModelVersionRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.TypeReferenceRepository;
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
class InputSnapshotFactoryTest extends AbstractPostgresIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final InputSnapshotFactory snapshotFactory;
    private final CommandService commandService;
    private final UserAccountRepository userAccountRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UmlModelRepository modelRepository;
    private final ModelVersionRepository versionRepository;
    private final TypeReferenceRepository typeReferenceRepository;
    private final DiagramViewRepository viewRepository;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    InputSnapshotFactoryTest(InputSnapshotFactory snapshotFactory, CommandService commandService,
            UserAccountRepository userAccountRepository, ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository, UmlModelRepository modelRepository,
            ModelVersionRepository versionRepository, TypeReferenceRepository typeReferenceRepository,
            DiagramViewRepository viewRepository, TransactionTemplate transactionTemplate) {
        this.snapshotFactory = snapshotFactory;
        this.commandService = commandService;
        this.userAccountRepository = userAccountRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.modelRepository = modelRepository;
        this.versionRepository = versionRepository;
        this.typeReferenceRepository = typeReferenceRepository;
        this.viewRepository = viewRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Test
    void snapshotsOfSameUnchangedStateHaveSameSnapshotHash() {
        Fixture fixture = fixture("unchanged-snapshot");
        UUID classifierId = createClassifier(fixture, 0, "Account");
        addAttribute(fixture, 1, classifierId, UUID.randomUUID(), "name", "String");
        ModelVersion version = currentVersion(fixture);

        InputSnapshot first = snapshotFactory.create(version);
        InputSnapshot second = snapshotFactory.create(version);

        assertThat(first.source()).isEqualTo("USER_DOMAIN_CLASS_DIAGRAM");
        assertThat(first.snapshotHash()).isEqualTo(second.snapshotHash());
        assertThat(first.canonicalProjection()).isEqualTo(second.canonicalProjection());
        assertThat(first.canonicalProjection()).contains("classifiers|", "attributes|", "typeReferences|",
                "primitiveName=String");
        assertThat(first.validationState()).isEqualTo(ValidationState.VALID);
        reportHashComparison("unchanged state", first, second);
    }

    @Test
    void renamingAttributeChangesSnapshotHash() {
        Fixture fixture = fixture("rename-attribute");
        UUID classifierId = createClassifier(fixture, 0, "Account");
        UUID attributeId = UUID.randomUUID();
        addAttribute(fixture, 1, classifierId, attributeId, "name", "String");
        InputSnapshot before = snapshotFactory.create(currentVersion(fixture));

        assertAccepted(command(fixture, "attribute.update", 2, Map.of("name", "displayName"),
                List.of(new Target("attribute", attributeId))));
        InputSnapshot after = snapshotFactory.create(currentVersion(fixture));

        assertThat(after.snapshotHash()).isNotEqualTo(before.snapshotHash());
        assertThat(after.modelStateHash()).isNotEqualTo(before.modelStateHash());
        assertThat(after.canonicalProjection()).contains("name=displayName");
        reportHashComparison("attribute rename", before, after);
    }

    @Test
    void changingTypeReferencePrimitiveNameChangesSnapshotHash() {
        Fixture fixture = fixture("change-primitive-name");
        UUID classifierId = createClassifier(fixture, 0, "Account");
        addAttribute(fixture, 1, classifierId, UUID.randomUUID(), "name", "String");
        ModelVersion version = currentVersion(fixture);
        InputSnapshot before = snapshotFactory.create(version);

        transactionTemplate.execute(status -> {
            TypeReference reference = typeReferenceRepository
                    .findFirstByModelIdAndPrimitiveName(fixture.model().getId(), "String").orElseThrow();
            reference.setPrimitiveName("Integer");
            typeReferenceRepository.saveAndFlush(reference);
            return null;
        });
        InputSnapshot after = snapshotFactory.create(version);

        assertThat(after.snapshotHash()).isNotEqualTo(before.snapshotHash());
        assertThat(after.modelStateHash()).isEqualTo(before.modelStateHash());
        assertThat(before.canonicalProjection()).contains("primitiveName=String");
        assertThat(after.canonicalProjection()).contains("primitiveName=Integer");
        reportHashComparison("type-reference primitive name", before, after);
    }

    @Test
    void renamingClassifierChangesSnapshotHash() {
        SemanticFixture fixture = semanticFixture("rename-classifier");
        InputSnapshot before = snapshotFactory.create(currentVersion(fixture.fixture()));

        assertAccepted(commandAtCurrentRevision(fixture.fixture(), "classifier.rename", Map.of("name", "Customer"),
                List.of(new Target("classifier", fixture.classifierId()))));
        InputSnapshot after = snapshotFactory.create(currentVersion(fixture.fixture()));

        assertThat(after.snapshotHash()).isNotEqualTo(before.snapshotHash());
        assertThat(after.modelStateHash()).isNotEqualTo(before.modelStateHash());
        assertThat(after.classifiers()).anySatisfy(classifier -> {
            assertThat(classifier.id()).isEqualTo(fixture.classifierId());
            assertThat(classifier.name()).isEqualTo("Customer");
        });
        reportHashComparison("classifier rename", before, after);
    }

    @Test
    void updatingOperationChangesSnapshotHash() {
        SemanticFixture fixture = semanticFixture("update-operation");
        InputSnapshot before = snapshotFactory.create(currentVersion(fixture.fixture()));

        assertAccepted(commandAtCurrentRevision(fixture.fixture(), "operation.update", Map.of("name", "loadCustomer"),
                List.of(new Target("operation", fixture.operationId()))));
        InputSnapshot after = snapshotFactory.create(currentVersion(fixture.fixture()));

        assertThat(after.snapshotHash()).isNotEqualTo(before.snapshotHash());
        assertThat(after.modelStateHash()).isNotEqualTo(before.modelStateHash());
        assertThat(after.operations()).anySatisfy(operation -> {
            assertThat(operation.id()).isEqualTo(fixture.operationId());
            assertThat(operation.name()).isEqualTo("loadCustomer");
        });
        reportHashComparison("operation update", before, after);
    }

    @Test
    void updatingParameterChangesSnapshotHash() {
        SemanticFixture fixture = semanticFixture("update-parameter");
        InputSnapshot before = snapshotFactory.create(currentVersion(fixture.fixture()));

        assertAccepted(commandAtCurrentRevision(fixture.fixture(), "parameter.update", Map.of("name", "addressId"),
                List.of(new Target("parameter", fixture.parameterId()))));
        InputSnapshot after = snapshotFactory.create(currentVersion(fixture.fixture()));

        assertThat(after.snapshotHash()).isNotEqualTo(before.snapshotHash());
        assertThat(after.modelStateHash()).isNotEqualTo(before.modelStateHash());
        assertThat(after.parameters()).anySatisfy(parameter -> {
            assertThat(parameter.id()).isEqualTo(fixture.parameterId());
            assertThat(parameter.name()).isEqualTo("addressId");
        });
        reportHashComparison("parameter update", before, after);
    }

    @Test
    void updatingRelationshipChangesSnapshotHash() {
        SemanticFixture fixture = semanticFixture("update-relationship");
        InputSnapshot before = snapshotFactory.create(currentVersion(fixture.fixture()));

        assertAccepted(commandAtCurrentRevision(fixture.fixture(), "relationship.update",
                Map.of("name", "ownsCustomer"), List.of(new Target("relationship", fixture.relationshipId()))));
        InputSnapshot after = snapshotFactory.create(currentVersion(fixture.fixture()));

        assertThat(after.snapshotHash()).isNotEqualTo(before.snapshotHash());
        assertThat(after.modelStateHash()).isNotEqualTo(before.modelStateHash());
        assertThat(after.relationships()).anySatisfy(relationship -> {
            assertThat(relationship.id()).isEqualTo(fixture.relationshipId());
            assertThat(relationship.name()).isEqualTo("ownsCustomer");
        });
        reportHashComparison("relationship update", before, after);
    }

    @Test
    void updatingRelationshipEndChangesSnapshotHash() {
        SemanticFixture fixture = semanticFixture("update-relationship-end");
        InputSnapshot before = snapshotFactory.create(currentVersion(fixture.fixture()));

        assertAccepted(commandAtCurrentRevision(fixture.fixture(), "relationship.update",
                Map.of("source", Map.of("role", "holder")),
                List.of(new Target("relationship", fixture.relationshipId()))));
        InputSnapshot after = snapshotFactory.create(currentVersion(fixture.fixture()));

        assertThat(after.snapshotHash()).isNotEqualTo(before.snapshotHash());
        assertThat(after.modelStateHash()).isNotEqualTo(before.modelStateHash());
        assertThat(after.relationshipEnds()).anySatisfy(end -> {
            assertThat(end.relationshipId()).isEqualTo(fixture.relationshipId());
            assertThat(end.ordinal()).isEqualTo((short) 1);
            assertThat(end.role()).isEqualTo("holder");
        });
        reportHashComparison("relationship end update", before, after);
    }

    @Test
    void updatingConstraintChangesSnapshotHash() {
        SemanticFixture fixture = semanticFixture("update-constraint");
        InputSnapshot before = snapshotFactory.create(currentVersion(fixture.fixture()));

        assertAccepted(commandAtCurrentRevision(fixture.fixture(), "constraint.update",
                Map.of("expression", "self.name != ''"),
                List.of(new Target("constraint", fixture.constraintId()))));
        InputSnapshot after = snapshotFactory.create(currentVersion(fixture.fixture()));

        assertThat(after.snapshotHash()).isNotEqualTo(before.snapshotHash());
        assertThat(after.modelStateHash()).isNotEqualTo(before.modelStateHash());
        assertThat(after.constraints()).anySatisfy(constraint -> {
            assertThat(constraint.id()).isEqualTo(fixture.constraintId());
            assertThat(constraint.expression()).isEqualTo("self.name != ''");
        });
        reportHashComparison("constraint update", before, after);
    }

    @Test
    void sharedHashFixtureContainsAtLeastOneElementInEveryCanonicalGroup() {
        SemanticFixture fixture = semanticFixture("complete-hash-fixture");
        InputSnapshot snapshot = snapshotFactory.create(currentVersion(fixture.fixture()));

        assertThat(snapshot.classifiers()).isNotEmpty();
        assertThat(snapshot.attributes()).isNotEmpty();
        assertThat(snapshot.operations()).isNotEmpty();
        assertThat(snapshot.parameters()).isNotEmpty();
        assertThat(snapshot.typeReferences()).isNotEmpty();
        assertThat(snapshot.relationships()).isNotEmpty();
        assertThat(snapshot.relationshipEnds()).isNotEmpty();
        assertThat(snapshot.constraints()).isNotEmpty();
    }

    @Test
    void layoutOnlyChangesDoNotChangeSnapshotHashButChangeModelStateHash() {
        Fixture fixture = fixture("layout-exclusion");
        UUID sourceId = createClassifier(fixture, 0, "Source");
        UUID targetId = createClassifier(fixture, 1, "Target");
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> relationshipPayload = Map.of(
                "id", relationshipId,
                "kind", "ASSOCIATION",
                "source", Map.of("classifierId", sourceId),
                "target", Map.of("classifierId", targetId),
                "waypoints", List.of(Map.of("x", 10, "y", 20)));
        assertAccepted(command(fixture, "relationship.create", 2, relationshipPayload,
                List.of(new Target("classifier", sourceId), new Target("classifier", targetId))));

        InputSnapshot before = snapshotFactory.create(currentVersion(fixture));
        assertAccepted(command(fixture, "geometry.set", 3,
                Map.of("targetType", "CLASSIFIER", "viewId", fixture.defaultViewId(), "targetId", sourceId,
                        "x", 30, "y", 40, "w", 120, "h", 80, "zOrder", 2), List.of()));
        InputSnapshot afterMove = snapshotFactory.create(currentVersion(fixture));
        assertLayoutOnlyChange(before, afterMove, "classifier geometry");

        assertAccepted(command(fixture, "geometry.set", 4,
                Map.of("targetType", "RELATIONSHIP", "viewId", fixture.defaultViewId(), "targetId", relationshipId,
                        "waypoints", List.of(Map.of("x", 35, "y", 45))), List.of()));
        InputSnapshot afterWaypoint = snapshotFactory.create(currentVersion(fixture));
        assertLayoutOnlyChange(afterMove, afterWaypoint, "connector waypoint");

        assertAccepted(command(fixture, "view.create", 5,
                Map.of("id", UUID.randomUUID(), "name", "Alternative"), List.of()));
        InputSnapshot afterAdditionalView = snapshotFactory.create(currentVersion(fixture));
        assertLayoutOnlyChange(afterWaypoint, afterAdditionalView, "additional diagram view");
        assertThat(afterAdditionalView.snapshotHash()).isEqualTo(before.snapshotHash());
        assertThat(afterAdditionalView.modelStateHash()).isNotEqualTo(before.modelStateHash());
    }

    @Test
    void invalidVersionIsRejectedAndNamesItsState() {
        ModelVersion version = versionWithState(fixture("invalid-state"), ValidationState.INVALID);

        assertThatThrownBy(() -> snapshotFactory.create(version))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID");
    }

    @Test
    void pendingVersionIsRejectedAndNamesItsState() {
        ModelVersion version = versionWithState(fixture("pending-state"), ValidationState.PENDING);

        assertThatThrownBy(() -> snapshotFactory.create(version))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void snapshotHashIs64LowercaseHexCharacters() {
        Fixture fixture = fixture("hash-shape");
        createClassifier(fixture, 0, "Account");

        assertThat(snapshotFactory.create(currentVersion(fixture)).snapshotHash()).matches("[0-9a-f]{64}");
    }

    private void assertLayoutOnlyChange(InputSnapshot before, InputSnapshot after, String scenario) {
        assertThat(after.snapshotHash()).isEqualTo(before.snapshotHash());
        assertThat(after.modelStateHash()).isNotEqualTo(before.modelStateHash());
        reportHashComparison(scenario, before, after);
    }

    private static void reportHashComparison(String scenario, InputSnapshot before, InputSnapshot after) {
        System.out.printf("HASH_EVIDENCE scenario=%s snapshotHash=%s -> %s modelStateHash=%s -> %s%n",
                scenario, before.snapshotHash(), after.snapshotHash(), before.modelStateHash(), after.modelStateHash());
    }

    private UUID createClassifier(Fixture fixture, long revision, String name) {
        UUID classifierId = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", revision,
                Map.of("id", classifierId, "name", name, "kind", "CLASS"), List.of()));
        return classifierId;
    }

    private void addAttribute(Fixture fixture, long revision, UUID classifierId, UUID attributeId, String name,
            String primitiveName) {
        assertAccepted(command(fixture, "attribute.add", revision,
                Map.of("id", attributeId, "name", name, "type", Map.of("primitiveName", primitiveName)),
                List.of(new Target("classifier", classifierId))));
    }

    private SemanticFixture semanticFixture(String label) {
        Fixture fixture = fixture(label);
        UUID classifierId = createClassifier(fixture, 0, "Account");
        UUID addressClassifierId = createClassifier(fixture, 1, "Address");
        addAttribute(fixture, 2, classifierId, UUID.randomUUID(), "email", "String");

        UUID operationId = UUID.randomUUID();
        assertAccepted(command(fixture, "operation.add", 3,
                Map.of("id", operationId, "name", "loadAddress",
                        "returnType", Map.of("classifierId", addressClassifierId)),
                List.of(new Target("classifier", classifierId))));

        UUID parameterId = UUID.randomUUID();
        assertAccepted(command(fixture, "parameter.add", 4,
                Map.of("id", parameterId, "name", "address", "type", Map.of("primitiveName", "Boolean")),
                List.of(new Target("operation", operationId))));

        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> relationshipPayload = Map.of(
                "id", relationshipId,
                "kind", "ASSOCIATION",
                "name", "ownsAddress",
                "source", Map.of("classifierId", classifierId, "role", "owner"),
                "target", Map.of("classifierId", addressClassifierId, "role", "address"));
        assertAccepted(command(fixture, "relationship.create", 5, relationshipPayload,
                List.of(new Target("classifier", classifierId), new Target("classifier", addressClassifierId))));

        UUID constraintId = UUID.randomUUID();
        assertAccepted(command(fixture, "constraint.add", 6,
                Map.of("id", constraintId, "ownerType", "CLASSIFIER", "ownerId", classifierId,
                        "expression", "self.name <> ''", "severity", "ERROR"),
                List.of(new Target("classifier", classifierId))));

        return new SemanticFixture(fixture, classifierId, operationId, parameterId, relationshipId, constraintId);
    }

    private CommandResult commandAtCurrentRevision(Fixture fixture, String type, Object payload, List<Target> targets) {
        return command(fixture, type, currentVersion(fixture).getRevision(), payload, targets);
    }

    private CommandResult command(Fixture fixture, String type, long revision, Object payload, List<Target> targets) {
        return commandService.execute(new OperationEnvelope(UUID.randomUUID(), type, fixture.author(),
                fixture.projectId(), fixture.model().getId(), revision, targets, List.of(), payload,
                CommandOrigin.MANUAL, null, UUID.randomUUID().toString()));
    }

    private static void assertAccepted(CommandResult result) {
        assertThat(result.status()).withFailMessage("diagnostics: %s", result.diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);
    }

    private ModelVersion currentVersion(Fixture fixture) {
        return versionRepository.findTopByModelIdOrderByRevisionDesc(fixture.model().getId()).orElseThrow();
    }

    private ModelVersion versionWithState(Fixture fixture, ValidationState state) {
        return transactionTemplate.execute(status -> {
            UmlModel model = modelRepository.findById(fixture.model().getId()).orElseThrow();
            long revision = model.getCurrentRevision() + 1;
            return versionRepository.saveAndFlush(ModelVersion.create(model, revision, model.getCurrentRevision(),
                    "a".repeat(64), state, null));
        });
    }

    private Fixture fixture(String label) {
        return transactionTemplate.execute(status -> {
            UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            UserAccount author = userAccountRepository.save(UserAccount.create(
                    "snapshot-" + UUID.randomUUID(), "Snapshot Test", "snapshot-" + UUID.randomUUID() + "@example.test"));
            Project project = projectRepository.save(Project.create(owner, "Snapshot " + label, "input snapshot tests"));
            UmlModel model = modelRepository.save(UmlModel.create(project, "Snapshot Model"));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            projectMemberRepository.save(ProjectMember.create(project, author, ProjectMemberRole.EDITOR));
            DiagramView defaultView = viewRepository.save(DiagramView.create(model, "Main", "CLASS", true));
            return new Fixture(project.getId(), model, new Author(author.getId(), "snapshot-test"), defaultView.getId());
        });
    }

    private record Fixture(UUID projectId, UmlModel model, Author author, UUID defaultViewId) {
    }

    private record SemanticFixture(Fixture fixture, UUID classifierId, UUID operationId, UUID parameterId,
            UUID relationshipId, UUID constraintId) {
    }
}
