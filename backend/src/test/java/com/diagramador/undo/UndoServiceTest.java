package com.diagramador.undo;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.api.ProjectApiService;
import com.diagramador.command.CommandOrigin;
import com.diagramador.command.CommandResult;
import com.diagramador.command.CommandStatus;
import com.diagramador.command.OperationEnvelope;
import com.diagramador.command.Precondition;
import com.diagramador.command.Target;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.UmlModel;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.ModelCommandRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
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

@SpringBootTest
class UndoServiceTest {

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

    private final ProjectApiService projectApiService;
    private final UndoService undoService;
    private final UndoLogRepository undoLogRepository;
    private final ModelCommandRepository commandRepository;
    private final ProjectRepository projectRepository;
    private final UmlModelRepository modelRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserAccountRepository userAccountRepository;
    private final ClassifierRepository classifierRepository;
    private final UmlAttributeRepository attributeRepository;
    private final UmlOperationRepository operationRepository;
    private final UmlRelationshipRepository relationshipRepository;
    private final RelationshipEndRepository relationshipEndRepository;
    private final CurrentUserContext currentUserContext;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    UndoServiceTest(ProjectApiService projectApiService, UndoService undoService,
            UndoLogRepository undoLogRepository, ModelCommandRepository commandRepository,
            ProjectRepository projectRepository, UmlModelRepository modelRepository,
            ProjectMemberRepository projectMemberRepository, UserAccountRepository userAccountRepository,
            ClassifierRepository classifierRepository, UmlAttributeRepository attributeRepository,
            UmlOperationRepository operationRepository, UmlRelationshipRepository relationshipRepository,
            RelationshipEndRepository relationshipEndRepository, CurrentUserContext currentUserContext,
            TransactionTemplate transactionTemplate) {
        this.projectApiService = projectApiService;
        this.undoService = undoService;
        this.undoLogRepository = undoLogRepository;
        this.commandRepository = commandRepository;
        this.projectRepository = projectRepository;
        this.modelRepository = modelRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.userAccountRepository = userAccountRepository;
        this.classifierRepository = classifierRepository;
        this.attributeRepository = attributeRepository;
        this.operationRepository = operationRepository;
        this.relationshipRepository = relationshipRepository;
        this.relationshipEndRepository = relationshipEndRepository;
        this.currentUserContext = currentUserContext;
        this.transactionTemplate = transactionTemplate;
    }

    @AfterEach
    void clearSession() {
        currentUserContext.clear();
    }

    @Test
    void undoOfClassifierCreateAppliesTheInverseDeleteThroughTheNormalExecutor() {
        Fixture fixture = fixture("undo-create");
        UUID classifierId = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", 0,
                Map.of("id", classifierId, "name", "Order", "kind", "CLASS"), List.of()));

        UndoResult result = undoService.undo(fixture.project().getId());

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(result.resultRevision()).isEqualTo(2L);
        assertThat(classifierRepository.findById(classifierId)).isEmpty();
        assertThat(entryFor(fixture).getUndoneAt()).isNotNull();
    }

    @Test
    void undoOfClassifierRenameRestoresThePreviousName() {
        Fixture fixture = fixture("undo-rename");
        UUID classifierId = createClassifier(fixture, 0, "Order");
        assertAccepted(command(fixture, "classifier.rename", 1, Map.of("name", "Cliente"),
                List.of(new Target("classifier", classifierId))));

        UndoResult result = undoService.undo(fixture.project().getId());

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getName()).isEqualTo("Order");
    }

    @Test
    void undoOfClassifierSetAbstractRestoresThePreviousFlag() {
        Fixture fixture = fixture("undo-set-abstract");
        UUID classifierId = createClassifier(fixture, 0, "Order");
        assertAccepted(command(fixture, "classifier.setAbstract", 1, Map.of("isAbstract", true),
                List.of(new Target("classifier", classifierId))));

        UndoResult result = undoService.undo(fixture.project().getId());

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(classifierRepository.findById(classifierId).orElseThrow().isAbstract()).isFalse();
    }

    @Test
    void undoOfClassifierSetStoreRestoresThePreviousStoreIncludingNoOverride() {
        Fixture fixture = fixture("undo-set-store");
        UUID classifierId = createClassifier(fixture, 0, "Order");
        assertAccepted(command(fixture, "classifier.setStore", 1, Map.of("store", "DOCUMENT"),
                List.of(new Target("classifier", classifierId))));

        UndoResult result = undoService.undo(fixture.project().getId());

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getStoreOverride()).isNull();
    }

    @Test
    void undoOfAttributeAddAppliesTheInverseRemove() {
        Fixture fixture = fixture("undo-attribute-add");
        UUID classifierId = createClassifier(fixture, 0, "Order");
        UUID attributeId = UUID.randomUUID();
        assertAccepted(command(fixture, "attribute.add", 1,
                Map.of("id", attributeId, "name", "number", "type", Map.of("primitiveName", "String")),
                List.of(new Target("classifier", classifierId))));

        UndoResult result = undoService.undo(fixture.project().getId());

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(attributeRepository.findById(attributeId)).isEmpty();
    }

    @Test
    void undoOfAttributeRemoveRecreatesThePreviousValue() {
        Fixture fixture = fixture("undo-attribute-remove");
        UUID classifierId = createClassifier(fixture, 0, "Order");
        UUID attributeId = UUID.randomUUID();
        assertAccepted(command(fixture, "attribute.add", 1,
                Map.of("id", attributeId, "name", "number", "type", Map.of("primitiveName", "String")),
                List.of(new Target("classifier", classifierId))));
        assertAccepted(command(fixture, "attribute.remove", 2, Map.of(),
                List.of(new Target("attribute", attributeId))));

        UndoResult result = undoService.undo(fixture.project().getId());

        assertThat(result.status()).isEqualTo("ACCEPTED");
        transactionTemplate.execute(status -> {
            var restored = attributeRepository.findById(attributeId).orElseThrow();
            assertThat(restored.getName()).isEqualTo("number");
            assertThat(restored.getVisibility()).isEqualTo("PUBLIC");
            assertThat(restored.getMultiplicity()).isEqualTo(com.diagramador.domain.MultiplicityKind.EXACTLY_ONE);
            assertThat(restored.getOrdinal()).isZero();
            assertThat(restored.getTypeReference().getPrimitiveName()).isEqualTo("String");
            assertThat(restored.getClassifier().getId()).isEqualTo(classifierId);
            return null;
        });
    }

    @Test
    void undoOfAttributeUpdateRestoresEveryPreviousValue() {
        Fixture fixture = fixture("undo-attribute-update");
        UUID classifierId = createClassifier(fixture, 0, "Order");
        UUID attributeId = UUID.randomUUID();
        assertAccepted(command(fixture, "attribute.add", 1,
                Map.of("id", attributeId, "name", "number", "type", Map.of("primitiveName", "String")),
                List.of(new Target("classifier", classifierId))));
        assertAccepted(command(fixture, "attribute.update", 2,
                Map.of("name", "codigo", "type", Map.of("primitiveName", "Integer"), "visibility", "PRIVATE"),
                List.of(new Target("attribute", attributeId))));

        UndoResult result = undoService.undo(fixture.project().getId());

        assertThat(result.status()).isEqualTo("ACCEPTED");
        transactionTemplate.execute(status -> {
            var restored = attributeRepository.findById(attributeId).orElseThrow();
            assertThat(restored.getName()).isEqualTo("number");
            assertThat(restored.getVisibility()).isEqualTo("PUBLIC");
            assertThat(restored.getTypeReference().getPrimitiveName()).isEqualTo("String");
            return null;
        });
    }

    @Test
    void undoRestoresRangeBoundsOfAnUpdatedAndOfARemovedAttribute() {
        Fixture fixture = fixture("undo-attribute-range");
        UUID classifierId = createClassifier(fixture, 0, "Order");
        UUID attributeId = UUID.randomUUID();
        assertAccepted(command(fixture, "attribute.add", 1,
                Map.of("id", attributeId, "name", "tags", "type", Map.of("primitiveName", "String"),
                        "multiplicity", "RANGE", "lowerBound", 2),
                List.of(new Target("classifier", classifierId))));
        assertAccepted(command(fixture, "attribute.update", 2,
                Map.of("multiplicity", "RANGE", "lowerBound", 1, "upperBound", 4),
                List.of(new Target("attribute", attributeId))));

        assertThat(undoService.undo(fixture.project().getId()).status()).isEqualTo("ACCEPTED");
        transactionTemplate.execute(status -> {
            var restored = attributeRepository.findById(attributeId).orElseThrow();
            assertThat(restored.getLowerBound()).isEqualTo(2);
            assertThat(restored.getUpperBound()).isNull();
            return null;
        });

        assertAccepted(command(fixture, "attribute.remove", 4, Map.of(),
                List.of(new Target("attribute", attributeId))));
        assertThat(undoService.undo(fixture.project().getId()).status()).isEqualTo("ACCEPTED");
        transactionTemplate.execute(status -> {
            var restored = attributeRepository.findById(attributeId).orElseThrow();
            assertThat(restored.getMultiplicity()).isEqualTo(com.diagramador.domain.MultiplicityKind.RANGE);
            assertThat(restored.getLowerBound()).isEqualTo(2);
            assertThat(restored.getUpperBound()).isNull();
            return null;
        });
    }

    @Test
    void undoOfRelationshipCreateAppliesTheInverseDelete() {
        Fixture fixture = fixture("undo-relationship-create");
        UUID sourceId = createClassifier(fixture, 0, "Order");
        UUID targetId = createClassifier(fixture, 1, "Customer");
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(command(fixture, "relationship.create", 2,
                Map.of("id", relationshipId, "kind", "ASSOCIATION",
                        "source", Map.of("classifierId", sourceId.toString()),
                        "target", Map.of("classifierId", targetId.toString())),
                List.of(new Target("classifier", sourceId), new Target("classifier", targetId))));

        UndoResult result = undoService.undo(fixture.project().getId());

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(relationshipRepository.findById(relationshipId)).isEmpty();
    }

    @Test
    void undoOfRelationshipDeleteRecreatesTheRelationshipWithItsEnds() {
        Fixture fixture = fixture("undo-relationship-delete");
        UUID sourceId = createClassifier(fixture, 0, "Order");
        UUID targetId = createClassifier(fixture, 1, "Customer");
        UUID relationshipId = UUID.randomUUID();
        createRelationship(fixture, 2, relationshipId, sourceId, targetId);
        assertAccepted(command(fixture, "relationship.delete", 3, Map.of(),
                List.of(new Target("relationship", relationshipId)),
                List.of(new Precondition("cascade_count", 3, null))));

        UndoResult result = undoService.undo(fixture.project().getId());

        assertThat(result.status()).isEqualTo("ACCEPTED");
        var restored = relationshipRepository.findById(relationshipId).orElseThrow();
        assertThat(restored.getKind()).isEqualTo(com.diagramador.domain.RelationshipKind.ASSOCIATION);
        List<com.diagramador.domain.RelationshipEnd> ends =
                relationshipEndRepository.findByRelationshipId(relationshipId);
        assertThat(ends).hasSize(2);
        assertThat(ends.stream().filter(end -> end.getOrdinal() == 1).findFirst().orElseThrow().getClassifier()
                .getId()).isEqualTo(sourceId);
        assertThat(ends.stream().filter(end -> end.getOrdinal() == 2).findFirst().orElseThrow().getClassifier()
                .getId()).isEqualTo(targetId);
    }

    @Test
    void undoOfRelationshipDeleteRestoresTheStereotypeOfADependency() {
        Fixture fixture = fixture("undo-relationship-delete-stereotype");
        UUID sourceId = createClassifier(fixture, 0, "Order");
        UUID targetId = createClassifier(fixture, 1, "Customer");
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(command(fixture, "relationship.create", 2,
                Map.of("id", relationshipId, "kind", "DEPENDENCY", "stereotype", "INCLUDE",
                        "source", Map.of("classifierId", sourceId.toString()),
                        "target", Map.of("classifierId", targetId.toString())),
                List.of(new Target("classifier", sourceId), new Target("classifier", targetId))));
        assertAccepted(command(fixture, "relationship.delete", 3, Map.of(),
                List.of(new Target("relationship", relationshipId)),
                List.of(new Precondition("cascade_count", 3, null))));

        UndoResult result = undoService.undo(fixture.project().getId());

        assertThat(result.status()).isEqualTo("ACCEPTED");
        var restored = relationshipRepository.findById(relationshipId).orElseThrow();
        assertThat(restored.getKind()).isEqualTo(com.diagramador.domain.RelationshipKind.DEPENDENCY);
        assertThat(restored.getStereotype()).isEqualTo("INCLUDE");
    }

    @Test
    void undoOfRelationshipDeleteRestoresTheAssociationClassLink() {
        Fixture fixture = fixture("undo-relationship-delete-association-class");
        UUID sourceId = createClassifier(fixture, 0, "Student");
        UUID targetId = createClassifier(fixture, 1, "Course");
        UUID membershipId = createClassifier(fixture, 2, "Enrolment");
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(command(fixture, "relationship.create", 3,
                Map.of("id", relationshipId, "kind", "ASSOCIATION", "associationClassId", membershipId,
                        "source", Map.of("classifierId", sourceId.toString()),
                        "target", Map.of("classifierId", targetId.toString())),
                List.of(new Target("classifier", sourceId), new Target("classifier", targetId))));
        assertAccepted(command(fixture, "relationship.delete", 4, Map.of(),
                List.of(new Target("relationship", relationshipId)),
                List.of(new Precondition("cascade_count", 3, null))));

        UndoResult result = undoService.undo(fixture.project().getId());

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getAssociationClassId())
                .isEqualTo(membershipId);
    }

    @Test
    void undoOfRelationshipDeleteStillRestoresTheRelationshipWhenItsAssociationClassIsGone() {
        Fixture fixture = fixture("undo-relationship-delete-association-class-gone");
        UUID sourceId = createClassifier(fixture, 0, "Student");
        UUID targetId = createClassifier(fixture, 1, "Course");
        UUID membershipId = createClassifier(fixture, 2, "Enrolment");
        UUID relationshipId = UUID.randomUUID();
        assertAccepted(command(fixture, "relationship.create", 3,
                Map.of("id", relationshipId, "kind", "ASSOCIATION", "associationClassId", membershipId,
                        "source", Map.of("classifierId", sourceId.toString()),
                        "target", Map.of("classifierId", targetId.toString())),
                List.of(new Target("classifier", sourceId), new Target("classifier", targetId))));
        assertAccepted(command(fixture, "relationship.delete", 4, Map.of(),
                List.of(new Target("relationship", relationshipId)),
                List.of(new Precondition("cascade_count", 3, null))));
        // Another editor deletes the association class after this author deleted the relationship.
        currentUserContext.set(new CurrentUser(fixture.secondEditor().getId(), fixture.secondEditor().getUsername(),
                "Editor2"));
        assertAccepted(command(fixture, "classifier.delete", 5, Map.of(),
                List.of(new Target("classifier", membershipId)),
                List.of(new Precondition("cascade_count", 1, null))));
        currentUserContext.set(new CurrentUser(fixture.author().getId(), fixture.author().getUsername(), "Author"));

        UndoResult result = undoService.undo(fixture.project().getId());

        assertThat(result.status()).as("undo of the relationship delete: %s", result).isEqualTo("ACCEPTED");
        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getAssociationClassId()).isNull();
    }

    @Test
    void undoOfCreateWithDependentsIsRejectedWithCascadeRequiredWithoutWorking() {
        Fixture fixture = fixture("undo-cascade-required");
        UUID classifierId = createClassifier(fixture, 0, "Order");
        // A foreign member adds a dependent, so the owner's latest undoable entry is the create.
        currentUserContext.set(new CurrentUser(fixture.secondEditor().getId(), fixture.secondEditor().getUsername(),
                "Editor2"));
        assertAccepted(command(fixture, "attribute.add", 1,
                Map.of("id", UUID.randomUUID(), "name", "number", "type", Map.of("primitiveName", "String")),
                List.of(new Target("classifier", classifierId))));
        currentUserContext.set(new CurrentUser(fixture.author().getId(), fixture.author().getUsername(), "Author"));

        UndoResult result = undoService.undo(fixture.project().getId());

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.code()).isEqualTo("CASCADE_REQUIRED");
        assertThat(classifierRepository.findById(classifierId)).isPresent();
        assertThat(attributeRepository.findByClassifierId(classifierId)).hasSize(1);
        assertThat(modelRepository.findById(fixture.model().getId()).orElseThrow().getCurrentRevision())
                .isEqualTo(2L);
    }

    @Test
    void undoAfterAnInterleavedForeignChangeKeepsTheForeignChange() {
        Fixture fixture = fixture("undo-interleaved");
        UUID classifierId = createClassifier(fixture, 0, "Order");
        assertAccepted(command(fixture, "attribute.add", 1,
                Map.of("id", UUID.randomUUID(), "name", "number", "type", Map.of("primitiveName", "String")),
                List.of(new Target("classifier", classifierId))));
        // A foreign member interleaves an unrelated command of their own.
        currentUserContext.set(new CurrentUser(fixture.secondEditor().getId(), fixture.secondEditor().getUsername(),
                "Editor2"));
        assertAccepted(command(fixture, "classifier.rename", 2, Map.of("name", "X2"),
                List.of(new Target("classifier", classifierId))));
        currentUserContext.set(new CurrentUser(fixture.author().getId(), fixture.author().getUsername(), "Author"));

        UndoResult result = undoService.undo(fixture.project().getId());

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(attributeRepository.findByClassifierId(classifierId)).isEmpty();
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getName()).isEqualTo("X2");
    }

    @Test
    void redoReappliesTheOriginalCommandAsANewRedoCommand() {
        Fixture fixture = fixture("redo-restores");
        UUID classifierId = createClassifier(fixture, 0, "Order");
        assertAccepted(command(fixture, "classifier.rename", 1, Map.of("name", "Cliente"),
                List.of(new Target("classifier", classifierId))));
        assertThat(undoService.undo(fixture.project().getId()).status()).isEqualTo("ACCEPTED");
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getName()).isEqualTo("Order");

        UndoResult result = undoService.redo(fixture.project().getId());

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getName()).isEqualTo("Cliente");
        UndoLogEntry entry = entryFor(fixture);
        assertThat(entry.getRedoneAt()).isNotNull();
        assertThat(entry.getUndoneAt()).isNull();
    }

    @Test
    void undoAndRedoFollowLifoOrderAcrossSeveralEntries() {
        Fixture fixture = fixture("undo-lifo");
        UUID classifierId = createClassifier(fixture, 0, "A");
        assertAccepted(command(fixture, "classifier.rename", 1, Map.of("name", "B"),
                List.of(new Target("classifier", classifierId))));
        assertAccepted(command(fixture, "classifier.rename", 2, Map.of("name", "C"),
                List.of(new Target("classifier", classifierId))));

        assertThat(undoService.undo(fixture.project().getId()).status()).isEqualTo("ACCEPTED");
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getName()).isEqualTo("B");
        assertThat(undoService.undo(fixture.project().getId()).status()).isEqualTo("ACCEPTED");
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getName()).isEqualTo("A");

        assertThat(undoService.redo(fixture.project().getId()).status()).isEqualTo("ACCEPTED");
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getName()).isEqualTo("B");
        assertThat(undoService.redo(fixture.project().getId()).status()).isEqualTo("ACCEPTED");
        assertThat(classifierRepository.findById(classifierId).orElseThrow().getName()).isEqualTo("C");
    }

    @Test
    void xmiImportEntriesAreDeclaredNotUndoable() {
        Fixture fixture = fixture("undo-xmi-import");
        UUID classifierId = createClassifier(fixture, 0, "Order");
        // The import path does not flow through the capture hook, so the row is seeded the way
        // any future capture of an xmi.import command would leave it: latest, not undone.
        transactionTemplate.execute(status -> {
            com.diagramador.domain.UmlModel model = modelRepository.findById(fixture.model().getId()).orElseThrow();
            com.diagramador.domain.UserAccount author = userAccountRepository
                    .findById(fixture.author().getId()).orElseThrow();
            com.diagramador.domain.ModelCommand command = commandRepository
                    .findById(entryFor(fixture).getCommand().getCommandId()).orElseThrow();
            undoLogRepository.save(UndoLogEntry.create(model, author, command, "xmi.import", null));
            return null;
        });

        UndoResult result = undoService.undo(fixture.project().getId());

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.code()).isEqualTo("XMI_IMPORT_NOT_UNDOABLE");
        assertThat(classifierRepository.findById(classifierId)).isPresent();
    }

    @Test
    void emptyLogIsReportedAsNothingToDo() {
        Fixture fixture = fixture("undo-empty");

        UndoResult undone = undoService.undo(fixture.project().getId());
        UndoResult redone = undoService.redo(fixture.project().getId());

        assertThat(undone.status()).isEqualTo("REJECTED");
        assertThat(undone.code()).isEqualTo("NOTHING_TO_UNDO");
        assertThat(redone.status()).isEqualTo("REJECTED");
        assertThat(redone.code()).isEqualTo("NOTHING_TO_REDO");
    }

    // --- helpers ------------------------------------------------------------------------

    private UndoLogEntry entryFor(Fixture fixture) {
        List<UndoLogEntry> entries = undoLogRepository
                .findByModel_IdOrderByCreatedAtAscIdAsc(fixture.model().getId());
        assertThat(entries).isNotEmpty();
        return entries.get(entries.size() - 1);
    }

    private UUID createClassifier(Fixture fixture, long revision, String name) {
        UUID classifierId = UUID.randomUUID();
        assertAccepted(command(fixture, "classifier.create", revision,
                Map.of("id", classifierId, "name", name, "kind", "CLASS"), List.of()));
        return classifierId;
    }

    private UUID createRelationship(Fixture fixture, long revision, UUID relationshipId, UUID sourceId,
            UUID targetId) {
        assertAccepted(command(fixture, "relationship.create", revision,
                Map.of("id", relationshipId, "kind", "ASSOCIATION",
                        "source", Map.of("classifierId", sourceId.toString()),
                        "target", Map.of("classifierId", targetId.toString())),
                List.of(new Target("classifier", sourceId), new Target("classifier", targetId))));
        return relationshipId;
    }

    private CommandResult command(Fixture fixture, String type, long revision, Map<String, Object> payload,
            List<Target> targets) {
        return command(fixture, type, revision, payload, targets, List.of());
    }

    private CommandResult command(Fixture fixture, String type, long revision, Map<String, Object> payload,
            List<Target> targets, List<Precondition> preconditions) {
        return projectApiService.executeCommand(fixture.project().getId(), new OperationEnvelope(
                UUID.randomUUID(), type, new com.diagramador.command.Author(fixture.author().getId(), "author"),
                fixture.project().getId(), fixture.model().getId(), revision, targets, preconditions, payload,
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
                    com.diagramador.domain.UserAccount.create("undo-" + UUID.randomUUID(), "Undo Author",
                            "undo-" + UUID.randomUUID() + "@example.test"));
            com.diagramador.domain.UserAccount secondEditor = userAccountRepository.save(
                    com.diagramador.domain.UserAccount.create("undo2-" + UUID.randomUUID(), "Undo Editor2",
                            "undo2-" + UUID.randomUUID() + "@example.test"));
            Project project = projectRepository.save(Project.create(owner, "Undo " + label, "undo tests"));
            UmlModel model = modelRepository.save(UmlModel.create(project, "Undo Model " + label));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            projectMemberRepository.save(ProjectMember.create(project, author, ProjectMemberRole.EDITOR));
            projectMemberRepository.save(ProjectMember.create(project, secondEditor, ProjectMemberRole.EDITOR));
            currentUserContext.set(new CurrentUser(author.getId(), author.getUsername(), "Author"));
            return new Fixture(project, model, author, secondEditor);
        });
    }

    private record Fixture(Project project, UmlModel model, com.diagramador.domain.UserAccount author,
            com.diagramador.domain.UserAccount secondEditor) {
    }
}
