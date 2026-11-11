package com.diagramador.xmi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramador.api.ModelResource;
import com.diagramador.command.Author;
import com.diagramador.command.CommandStatus;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.ExchangeDirection;
import com.diagramador.domain.ExchangeStatus;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.SeverityKind;
import com.diagramador.domain.Tombstone;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.DiagramViewRepository;
import com.diagramador.persistence.ModelCommandRepository;
import com.diagramador.persistence.ModelVersionRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.TombstoneRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UserAccountRepository;
import com.diagramador.persistence.XmiExchangeRepository;
import com.diagramador.persistence.XmiLossItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
class XmiImportServiceTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Path FIXTURE = Path.of("..", "docs", "fixtures", "xmi", "Prueba3.xml");
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

    @Autowired private XmiImportService importService;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private UmlModelRepository modelRepository;
    @Autowired private ClassifierRepository classifierRepository;
    @Autowired private UmlAttributeRepository attributeRepository;
    @Autowired private com.diagramador.persistence.UmlOperationRepository operationRepository;
    @Autowired private DiagramViewRepository viewRepository;
    @Autowired private com.diagramador.persistence.UmlRelationshipRepository relationshipRepository;
    @Autowired private ModelCommandRepository commandRepository;
    @Autowired private ModelVersionRepository versionRepository;
    @Autowired private TombstoneRepository tombstoneRepository;
    @Autowired private XmiExchangeRepository exchangeRepository;
    @Autowired private XmiLossItemRepository lossItemRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager entityManager;

    @Test
    void replaceImportsOneViewAndReportsEveryRemovedTarget() throws Exception {
        Fixture fixture = existingProject("Replace import");
        UUID oldViewId = fixture.defaultViewId();
        OldTargets oldTargets = transactionTemplate.execute(status -> {
            Classifier oldClassifier = classifierRepository.saveAndFlush(Classifier.create(
                    fixture.model(), null, ClassifierKind.CLASS, "OldClass", false));
            UmlAttribute oldAttribute = attributeRepository.saveAndFlush(UmlAttribute.create(oldClassifier, null,
                    "oldField", "ATTRIBUTE", "PUBLIC", MultiplicityKind.EXACTLY_ONE, null, 0));
            return new OldTargets(oldClassifier.getId(), oldAttribute.getId());
        });

        XmiImportService.XmiImportResult result = importBytes(fixture, XmiImportMode.REEMPLAZAR);

        assertThat(result.command().status()).as("diagnostics: %s", result.command().diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);
        assertThat(classifierRepository.findByModelId(fixture.model().getId())).hasSize(11);
        assertThat(viewRepository.findByModelId(fixture.model().getId())).singleElement()
                .satisfies(view -> assertThat(view.getId()).isNotEqualTo(oldViewId));
        assertThat(tombstoneRepository.findByModelId(fixture.model().getId()))
                .extracting(Tombstone::getTargetId)
                .containsExactlyInAnyOrder(oldTargets.classifierId(), oldTargets.attributeId(), oldViewId);
    }

    @Test
    void addKeepsExistingModelAndAddsTheImportedDiagramBesideIt() throws Exception {
        Fixture fixture = existingProject("Add import");
        UUID existingClassifierId = transactionTemplate.execute(status -> classifierRepository.saveAndFlush(
                Classifier.create(fixture.model(), null, ClassifierKind.CLASS, "ExistingClass", false)).getId());

        XmiImportService.XmiImportResult result = importBytes(fixture, XmiImportMode.AGREGAR);

        assertThat(result.command().status()).as("diagnostics: %s", result.command().diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);
        assertThat(classifierRepository.findById(existingClassifierId)).isPresent();
        assertThat(classifierRepository.findByModelId(fixture.model().getId())).hasSize(12);
        assertThat(viewRepository.findByModelId(fixture.model().getId())).hasSize(2)
                .filteredOn(DiagramView::isDefault).hasSize(1);
        assertThat(viewRepository.findByModelId(fixture.model().getId()))
                .anySatisfy(view -> assertThat(view.isDefault()).isFalse());
    }

    @Test
    void addNameCollisionIsDiagnosedWithoutPartialApplication() throws Exception {
        Fixture fixture = existingProject("Collision import");
        byte[] bytes = fixtureBytes();
        XmiReadingResult reading = new XmiReader().read(bytes);
        ModelResource.ClassifierResource colliding = reading.model().classifiers().stream()
                .filter(classifier -> classifier.namespaceId() == null).findFirst().orElseThrow();
        transactionTemplate.executeWithoutResult(status -> classifierRepository.saveAndFlush(Classifier.create(
                fixture.model(), null, colliding.kind(), colliding.name(), false)));
        int classifiersBefore = classifierRepository.findByModelId(fixture.model().getId()).size();
        int viewsBefore = viewRepository.findByModelId(fixture.model().getId()).size();

        XmiImportService.XmiImportResult result = importBytes(fixture, bytes, XmiImportMode.AGREGAR);

        assertThat(result.command().status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(result.command().diagnostics()).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.message()).contains("name", colliding.name()));
        assertThat(result.exchangeId()).isNull();
        assertThat(classifierRepository.findByModelId(fixture.model().getId())).hasSize(classifiersBefore);
        assertThat(viewRepository.findByModelId(fixture.model().getId())).hasSize(viewsBefore);
        assertThat(modelRepository.findById(fixture.model().getId()).orElseThrow().getCurrentRevision()).isZero();
        assertThat(exchangeRepository.findAll()).noneMatch(exchange ->
                exchange.getModel().getId().equals(fixture.model().getId()));
    }

    @Test
    void checksumMismatchIsRefusedBeforeAnyCommandOrExchangeIsWritten() throws Exception {
        Fixture fixture = existingProject("Checksum import");
        byte[] bytes = fixtureBytes();

        assertThatThrownBy(() -> importService.importBytes(fixture.project().getId(), fixture.author(), bytes,
                "0".repeat(64), XmiImportMode.REEMPLAZAR, UUID.randomUUID().toString()))
                .isInstanceOf(XmiImportService.ChecksumMismatchException.class);

        assertThat(modelRepository.findById(fixture.model().getId()).orElseThrow().getCurrentRevision()).isZero();
        assertThat(classifierRepository.findByModelId(fixture.model().getId())).isEmpty();
        assertThat(commandRepository.countByModelId(fixture.model().getId())).isZero();
        assertThat(versionRepository.countByModelId(fixture.model().getId())).isZero();
        assertThat(exchangeRepository.findAll()).noneMatch(exchange ->
                exchange.getModel().getId().equals(fixture.model().getId()));
    }

    @Test
    void exchangeAndLossRowsArePersistedAndReadBack() throws Exception {
        Fixture fixture = existingProject("Loss report import");
        byte[] bytes = fixtureBytes();

        XmiImportService.XmiImportResult result = importBytes(fixture, bytes, XmiImportMode.REEMPLAZAR);

        assertThat(result.command().status()).as("diagnostics: %s", result.command().diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);
        assertThat(result.exchangeId()).isNotNull();
        entityManager.clear();
        var persisted = exchangeRepository.findById(result.exchangeId()).orElseThrow();
        List<com.diagramador.domain.XmiLossItem> persistedLosses = lossItemRepository
                .findByExchangeId(result.exchangeId());
        assertThat(persisted.getDirection()).isEqualTo(ExchangeDirection.IMPORT);
        assertThat(persisted.getProfile()).isEqualTo(XmiImportService.PROFILE);
        assertThat(persisted.getModelRevision()).isEqualTo(result.command().resultRevision());
        assertThat(persisted.getChecksum()).isEqualTo(sha256(bytes));
        assertThat(persisted.getArtifactRef()).isNull();
        assertThat(persisted.getStatus()).isEqualTo(ExchangeStatus.PARTIAL);
        assertThat(persistedLosses).hasSize(result.losses().size()).allSatisfy(item -> {
            assertThat(item.getSeverity()).isEqualTo(SeverityKind.WARNING);
            assertThat(item.getExternalPath()).isNull();
            assertThat(item.getMessage()).contains("could not be represented");
        });
        assertThat(result.losses()).isNotEmpty();
    }

    @Test
    void anExportedDependencyStereotypeSurvivesImportIntoTheDatabase() throws Exception {
        Fixture fixture = existingProject("Stereotype import");
        byte[] bytes = stereotypedDependencyXmi("INCLUDE", "Stereotype view");

        XmiImportService.XmiImportResult result = importBytes(fixture, bytes, XmiImportMode.AGREGAR);

        assertThat(result.command().status()).as("diagnostics: %s", result.command().diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);
        assertThat(relationshipRepository.findAll()).filteredOn(relationship ->
                relationship.getModel().getId().equals(fixture.model().getId()))
                .singleElement().satisfies(relationship -> {
                    assertThat(relationship.getKind()).isEqualTo(com.diagramador.domain.RelationshipKind.DEPENDENCY);
                    assertThat(relationship.getStereotype()).isEqualTo("INCLUDE");
                });
    }

    @Test
    void theSameFileImportedIntoAnotherProjectKeepsTheStereotypeWhenItsIdentitiesAreRescoped() throws Exception {
        Fixture first = existingProject("Stereotype import project one");
        Fixture second = existingProject("Stereotype import project two");
        byte[] bytes = stereotypedDependencyXmi("ACCESS", "Shared view");

        // Identities are global, so the second project gets the same file under rescoped ids, which rebuilds
        // every relationship resource on the way in.
        XmiImportService.XmiImportResult one = importBytes(first, bytes, XmiImportMode.AGREGAR);
        assertThat(one.command().status()).as("first import: %s", one.command().diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);
        XmiImportService.XmiImportResult two = importBytes(second, bytes, XmiImportMode.AGREGAR);
        assertThat(two.command().status()).as("second import: %s", two.command().diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);

        var inFirst = relationshipRepository.findAll().stream()
                .filter(relationship -> relationship.getModel().getId().equals(first.model().getId())).toList();
        var inSecond = relationshipRepository.findAll().stream()
                .filter(relationship -> relationship.getModel().getId().equals(second.model().getId())).toList();
        assertThat(inFirst).singleElement().satisfies(relationship ->
                assertThat(relationship.getStereotype()).isEqualTo("ACCESS"));
        assertThat(inSecond).singleElement().satisfies(relationship -> {
            assertThat(relationship.getStereotype()).isEqualTo("ACCESS");
            assertThat(relationship.getId()).isNotEqualTo(inFirst.get(0).getId());
        });
    }

    @Test
    void anExportedAssociationClassSurvivesImportIntoTheDatabaseAndIntoAnotherProject() throws Exception {
        Fixture first = existingProject("Association class import project one");
        Fixture second = existingProject("Association class import project two");
        byte[] bytes = associationClassXmi("Association class view");

        XmiImportService.XmiImportResult one = importBytes(first, bytes, XmiImportMode.AGREGAR);
        assertThat(one.command().status()).as("first import: %s", one.command().diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);
        XmiImportService.XmiImportResult two = importBytes(second, bytes, XmiImportMode.AGREGAR);
        assertThat(two.command().status()).as("second import: %s", two.command().diagnostics())
                .isEqualTo(CommandStatus.ACCEPTED);

        for (Fixture fixture : List.of(first, second)) {
            var relationship = relationshipRepository.findAll().stream()
                    .filter(candidate -> candidate.getModel().getId().equals(fixture.model().getId()))
                    .findFirst().orElseThrow();
            assertThat(relationship.getAssociationClassId()).isNotNull();
            assertThat(classifierRepository.findById(relationship.getAssociationClassId()))
                    .get().satisfies(linked -> {
                        assertThat(linked.getName()).isEqualTo("Enrolment Association class view");
                        assertThat(linked.getModel().getId()).isEqualTo(fixture.model().getId());
                    });
        }
    }

    private byte[] associationClassXmi(String viewName) {
        java.util.function.LongFunction<UUID> id = value ->
                UUID.fromString("00000000-0000-0000-0000-%012x".formatted(value));
        java.util.function.BiFunction<Long, String, com.diagramador.api.ModelResource.ClassifierResource> clazz =
                (value, name) -> new com.diagramador.api.ModelResource.ClassifierResource(id.apply(value),
                        name + " " + viewName, ClassifierKind.CLASS, false, null, List.of(), List.of(), List.of());
        var association = new com.diagramador.api.ModelResource.RelationshipResource(id.apply(910), "ASSOCIATION",
                "studies", null, id.apply(902),
                List.of(new com.diagramador.api.ModelResource.RelationshipEndResource(id.apply(911), id.apply(900),
                                null, MultiplicityKind.EXACTLY_ONE, com.diagramador.domain.NavigationKind.UNSPECIFIED,
                                "none", (short) 1),
                        new com.diagramador.api.ModelResource.RelationshipEndResource(id.apply(912), id.apply(901),
                                null, MultiplicityKind.EXACTLY_ONE, com.diagramador.domain.NavigationKind.UNSPECIFIED,
                                "none", (short) 2)), List.of());
        var view = new com.diagramador.api.ModelResource.ViewResource(id.apply(300), viewName, true,
                List.of(new com.diagramador.api.ModelResource.GeometryResource(id.apply(500), com.diagramador.domain.TargetType.CLASSIFIER,
                                id.apply(900), 40, 60, 200, 100, 0),
                        new com.diagramador.api.ModelResource.GeometryResource(id.apply(501), com.diagramador.domain.TargetType.CLASSIFIER,
                                id.apply(901), 320, 60, 200, 100, 1),
                        new com.diagramador.api.ModelResource.GeometryResource(id.apply(502), com.diagramador.domain.TargetType.CLASSIFIER,
                                id.apply(902), 180, 240, 200, 100, 2)), List.of());
        var model = new com.diagramador.api.ModelResource(id.apply(1), id.apply(2), 3, id.apply(3), List.of(view),
                List.of(clazz.apply(900L, "Student"), clazz.apply(901L, "Course"), clazz.apply(902L, "Enrolment")),
                List.of(association));
        return new XmiSemanticEmitter().emit(model).document().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void exportedClassifierAttributeAndOperationStereotypesSurviveImportAndRescopedReimport() throws Exception {
        Fixture first = existingProject("Member stereotype import one");
        Fixture second = existingProject("Member stereotype import two");
        byte[] bytes = memberStereotypeXmi();

        for (Fixture fixture : List.of(first, second)) {
            XmiImportService.XmiImportResult result = importBytes(fixture, bytes, XmiImportMode.AGREGAR);
            assertThat(result.command().status()).as("diagnostics: %s", result.command().diagnostics())
                    .isEqualTo(CommandStatus.ACCEPTED);
            transactionTemplate.executeWithoutResult(status -> {
                var classifier = classifierRepository.findByModelId(fixture.model().getId()).stream()
                        .filter(candidate -> candidate.getName().equals("Stereotyped")).findFirst().orElseThrow();
                assertThat(classifier.getStereotype()).isEqualTo("ENTITY");
                assertThat(attributeRepository.findByClassifierId(classifier.getId())).singleElement()
                        .satisfies(attribute -> assertThat(attribute.getStereotype()).isEqualTo("UNIQUE"));
                assertThat(operationRepository.findByClassifierId(classifier.getId())).singleElement()
                        .satisfies(operation -> assertThat(operation.getStereotype()).isEqualTo("CREATE"));
            });
        }
    }

    private byte[] memberStereotypeXmi() {
        java.util.function.LongFunction<UUID> id = value ->
                UUID.fromString("00000000-0000-0000-0000-%012x".formatted(value));
        var attribute = new com.diagramador.api.ModelResource.AttributeResource(id.apply(941), "code", "ATTRIBUTE",
                "PUBLIC", MultiplicityKind.EXACTLY_ONE, null, null, "String", 0, null, null, "UNIQUE");
        var operation = new com.diagramador.api.ModelResource.OperationResource(id.apply(942), "make", "PUBLIC",
                false, false, null, null, 0, List.of(), "CREATE");
        var owner = new com.diagramador.api.ModelResource.ClassifierResource(id.apply(940), "Stereotyped",
                ClassifierKind.CLASS, false, null, List.of(attribute), List.of(operation), List.of(), null, null, null,
                "ENTITY");
        var view = new com.diagramador.api.ModelResource.ViewResource(id.apply(1300), "Member stereotype view", true,
                List.of(new com.diagramador.api.ModelResource.GeometryResource(id.apply(1500),
                        com.diagramador.domain.TargetType.CLASSIFIER, id.apply(940), 40, 60, 200, 100, 0)),
                List.of());
        var model = new com.diagramador.api.ModelResource(id.apply(1), id.apply(2), 3, id.apply(3), List.of(view),
                List.of(owner), List.of());
        return new XmiSemanticEmitter().emit(model).document().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] stereotypedDependencyXmi(String stereotype, String viewName) {
        java.util.function.LongFunction<UUID> id = value ->
                UUID.fromString("00000000-0000-0000-0000-%012x".formatted(value));
        var client = new com.diagramador.api.ModelResource.ClassifierResource(id.apply(900), "Client " + viewName,
                ClassifierKind.CLASS, false, null, List.of(), List.of(), List.of());
        var supplier = new com.diagramador.api.ModelResource.ClassifierResource(id.apply(901), "Supplier " + viewName,
                ClassifierKind.CLASS, false, null, List.of(), List.of(), List.of());
        var dependency = new com.diagramador.api.ModelResource.RelationshipResource(id.apply(910), "DEPENDENCY",
                "needs", stereotype,
                List.of(new com.diagramador.api.ModelResource.RelationshipEndResource(id.apply(911), id.apply(900),
                                null, MultiplicityKind.EXACTLY_ONE, com.diagramador.domain.NavigationKind.UNSPECIFIED,
                                "none", (short) 1),
                        new com.diagramador.api.ModelResource.RelationshipEndResource(id.apply(912), id.apply(901),
                                null, MultiplicityKind.EXACTLY_ONE, com.diagramador.domain.NavigationKind.UNSPECIFIED,
                                "none", (short) 2)), List.of());
        var view = new com.diagramador.api.ModelResource.ViewResource(id.apply(300), viewName, true,
                List.of(new com.diagramador.api.ModelResource.GeometryResource(id.apply(500), com.diagramador.domain.TargetType.CLASSIFIER,
                                id.apply(900), 40, 60, 200, 100, 0),
                        new com.diagramador.api.ModelResource.GeometryResource(id.apply(501), com.diagramador.domain.TargetType.CLASSIFIER,
                                id.apply(901), 320, 60, 200, 100, 1)), List.of());
        var model = new com.diagramador.api.ModelResource(id.apply(1), id.apply(2), 3, id.apply(3), List.of(view),
                List.of(client, supplier), List.of(dependency));
        return new XmiSemanticEmitter().emit(model).document().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private XmiImportService.XmiImportResult importBytes(Fixture fixture, XmiImportMode mode) throws Exception {
        return importBytes(fixture, fixtureBytes(), mode);
    }

    private XmiImportService.XmiImportResult importBytes(Fixture fixture, byte[] bytes, XmiImportMode mode)
            throws Exception {
        return importService.importBytes(fixture.project().getId(), fixture.author(), bytes, sha256(bytes), mode,
                UUID.randomUUID().toString());
    }

    private Fixture existingProject(String name) {
        return transactionTemplate.execute(status -> {
            UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            Project project = projectRepository.save(Project.create(owner, name, "XMI import integration test"));
            UmlModel model = modelRepository.save(UmlModel.create(project, name + " model"));
            memberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            DiagramView defaultView = viewRepository.save(DiagramView.create(model, "Existing diagram", "CLASS", true));
            return new Fixture(project, model, new Author(SEED_OWNER_ID, "seed-owner-local"), defaultView.getId());
        });
    }

    private byte[] fixtureBytes() throws Exception {
        return Files.readAllBytes(FIXTURE);
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Fixture(Project project, UmlModel model, Author author, UUID defaultViewId) {
    }

    private record OldTargets(UUID classifierId, UUID attributeId) {
    }
}
