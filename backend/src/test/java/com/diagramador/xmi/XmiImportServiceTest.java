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
    @Autowired private DiagramViewRepository viewRepository;
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
