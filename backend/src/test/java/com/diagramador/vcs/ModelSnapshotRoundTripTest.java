package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.domain.ChatSession;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.ConnectorPath;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.ElementGeometry;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.Project;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.SeverityKind;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlConstraint;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UmlOperation;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.domain.UserAccount;
import com.diagramador.persistence.ChatSessionRepository;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.ConnectorPathRepository;
import com.diagramador.persistence.DiagramViewRepository;
import com.diagramador.persistence.ElementGeometryRepository;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.TypeReferenceRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import com.diagramador.persistence.UserAccountRepository;
import com.diagramador.persistence.ViewStyle;
import com.diagramador.persistence.ViewStyleRepository;
import com.diagramador.vcs.ModelSnapshotService;
import com.diagramador.vcs.SnapshotModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class ModelSnapshotRoundTripTest extends AbstractPostgresIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private ModelSnapshotService snapshotService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UmlModelRepository modelRepository;

    @Autowired
    private ClassifierRepository classifierRepository;

    @Autowired
    private TypeReferenceRepository typeReferenceRepository;

    @Autowired
    private UmlAttributeRepository attributeRepository;

    @Autowired
    private UmlOperationRepository operationRepository;

    @Autowired
    private OperationParameterRepository parameterRepository;

    @Autowired
    private UmlRelationshipRepository relationshipRepository;

    @Autowired
    private RelationshipEndRepository relationshipEndRepository;

    @Autowired
    private UmlConstraintRepository constraintRepository;

    @Autowired
    private DiagramViewRepository viewRepository;

    @Autowired
    private ElementGeometryRepository geometryRepository;

    @Autowired
    private ConnectorPathRepository connectorPathRepository;

    @Autowired
    private ViewStyleRepository viewStyleRepository;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void capturesAndMaterializesTheCompleteModelIntoASecondModel() {
        Fixture source = createRichModel();
        SnapshotModel captured = snapshotService.capture(source.model());
        String sourceHash = snapshotService.stateHash(source.model());
        JsonNode sourceJson = snapshotService.toJson(captured);
        SnapshotModel decoded = snapshotService.fromJson(sourceJson);
        assertThat(snapshotService.toJson(decoded)).isEqualTo(sourceJson);

        UmlModel target = createEmptyModel();
        snapshotService.materialize(target, decoded);

        assertThat(snapshotService.stateHash(target)).isEqualTo(sourceHash);
        SnapshotModel targetSnapshot = snapshotService.capture(target);
        assertThat(snapshotService.toJson(targetSnapshot)).isEqualTo(sourceJson);
        assertThat(elementIds(targetSnapshot)).containsExactlyInAnyOrderElementsOf(elementIds(captured));
    }

    @Test
    void restoresTheEarlierStateAfterModelRowsAreMutated() {
        Fixture fixture = createRichModel();
        SnapshotModel earlier = snapshotService.capture(fixture.model());
        String originalHash = snapshotService.stateHash(fixture.model());

        transactionTemplate.executeWithoutResult(status -> {
            Classifier classifier = classifierRepository.findById(fixture.mutableClassifierId()).orElseThrow();
            classifier.setName("Mutated classifier name");
        });
        assertThat(snapshotService.stateHash(fixture.model())).isNotEqualTo(originalHash);

        snapshotService.materialize(fixture.model(), earlier);
        assertThat(snapshotService.stateHash(fixture.model())).isEqualTo(originalHash);
        assertThat(snapshotService.toJson(snapshotService.capture(fixture.model())))
                .isEqualTo(snapshotService.toJson(earlier));
    }

    @Test
    void materializationIsIdempotentAndDoesNotDuplicateRows() {
        Fixture fixture = createRichModel();
        SnapshotModel snapshot = snapshotService.capture(fixture.model());
        String expectedHash = snapshotService.stateHash(fixture.model());
        RowCounts expectedCounts = rowCounts(fixture.model().getId());

        snapshotService.materialize(fixture.model(), snapshot);
        snapshotService.materialize(fixture.model(), snapshot);

        assertThat(snapshotService.stateHash(fixture.model())).isEqualTo(expectedHash);
        assertThat(rowCounts(fixture.model().getId())).isEqualTo(expectedCounts);
    }

    @Test
    void materializationKeepsViewIdentityAndItsChatSession() {
        Fixture fixture = createRichModel();
        SnapshotModel snapshot = snapshotService.capture(fixture.model());
        UUID viewId = snapshot.views().getFirst().id();
        UUID sessionId = transactionTemplate.execute(status -> {
            DiagramView view = viewRepository.findById(viewId).orElseThrow();
            UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            return chatSessionRepository.save(ChatSession.create(view, owner, "Surviving session", false)).getId();
        });

        snapshotService.materialize(fixture.model(), snapshot);

        assertThat(viewRepository.findById(viewId)).isPresent();
        assertThat(chatSessionRepository.findById(sessionId)).isPresent();
        assertThat(chatSessionRepository.findById(sessionId).orElseThrow().getView().getId()).isEqualTo(viewId);
    }

    private Fixture createRichModel() {
        UmlModel model = createEmptyModel();
        UUID mutableClassifierId = transactionTemplate.execute(status -> {
            UmlModel managedModel = modelRepository.findById(model.getId()).orElseThrow();
            Classifier namespace = classifierRepository.save(Classifier.create(managedModel, null,
                    ClassifierKind.PACKAGE, "domain", false));
            Classifier abstractClassifier = classifierRepository.save(Classifier.create(managedModel,
                    namespace, ClassifierKind.ABSTRACT_CLASS, "BaseRecord", true));
            Classifier concreteClassifier = classifierRepository.save(Classifier.create(managedModel,
                    namespace, ClassifierKind.CLASS, "Customer", false));

            TypeReference primitiveType = typeReferenceRepository.save(TypeReference.forPrimitive(managedModel, "String"));
            TypeReference classifierType = typeReferenceRepository.save(
                    TypeReference.forClassifier(managedModel, abstractClassifier));
            attributeRepository.save(UmlAttribute.create(concreteClassifier, primitiveType, "name", "ATTRIBUTE",
                    "PRIVATE", MultiplicityKind.EXACTLY_ONE, "'anonymous'", 0));
            attributeRepository.save(UmlAttribute.create(concreteClassifier, classifierType, "record", "ATTRIBUTE",
                    "PROTECTED", MultiplicityKind.ZERO_OR_ONE, null, 1));

            UmlOperation operation = operationRepository.save(UmlOperation.create(concreteClassifier, "updateRecord",
                    classifierType, "PUBLIC", false, true, 0));
            parameterRepository.save(OperationParameter.create(operation, primitiveType, "name", "IN", 0));
            parameterRepository.save(OperationParameter.create(operation, classifierType, "base", "INOUT", 1));

            UmlRelationship relationship = relationshipRepository.save(
                    UmlRelationship.create(managedModel, RelationshipKind.AGGREGATION, "owns"));
            relationshipEndRepository.save(RelationshipEnd.create(relationship, concreteClassifier, (short) 1, "owner",
                    MultiplicityKind.EXACTLY_ONE, NavigationKind.NAVIGABLE, "SHARED"));
            relationshipEndRepository.save(RelationshipEnd.create(relationship, abstractClassifier, (short) 2, "record",
                    MultiplicityKind.ZERO_OR_MANY, NavigationKind.NOT_NAVIGABLE, "NONE"));

            constraintRepository.save(UmlConstraint.create(managedModel, TargetType.CLASSIFIER,
                    concreteClassifier.getId(), "self.name <> null", SeverityKind.ERROR));
            constraintRepository.save(UmlConstraint.create(managedModel, TargetType.RELATIONSHIP,
                    relationship.getId(), "owner->size() = 1", SeverityKind.WARNING));

            DiagramView classView = viewRepository.save(DiagramView.create(managedModel,
                    "Class view", "CLASS", true));
            DiagramView overview = viewRepository.save(DiagramView.create(managedModel,
                    "Overview", "OVERVIEW", false));
            geometryRepository.save(ElementGeometry.create(classView, TargetType.CLASSIFIER,
                    concreteClassifier.getId(), 40, 60, 180, 90, 2));
            geometryRepository.save(ElementGeometry.create(overview, TargetType.CLASSIFIER,
                    abstractClassifier.getId(), 240, 80, 160, 70, 1));
            connectorPathRepository.save(ConnectorPath.create(classView, relationship, 0, 120, 140));

            ObjectNode adjustment = objectMapper.createObjectNode();
            adjustment.put("axis", "H");
            adjustment.putArray("corridors").add(14).add(28);
            adjustment.putArray("levels").add(95);
            ObjectNode anchors = adjustment.putObject("anchors");
            anchors.putObject("source").put("side", "EAST").put("offset", 12);
            anchors.putObject("target").put("side", "WEST").put("offset", -8);
            viewStyleRepository.save(ViewStyle.create(classView, relationship.getId(), "ORTHOGONAL",
                    8, 3, true, adjustment));
            viewStyleRepository.save(ViewStyle.create(overview, relationship.getId(), "STRAIGHT",
                    0, 2, false, null));
            return concreteClassifier.getId();
        });
        return new Fixture(model, mutableClassifierId);
    }

    private UmlModel createEmptyModel() {
        return transactionTemplate.execute(status -> {
            UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            String suffix = UUID.randomUUID().toString();
            Project project = projectRepository.save(Project.create(owner, "Snapshot " + suffix, "Snapshot test"));
            return modelRepository.save(UmlModel.create(project, "Diagram " + suffix));
        });
    }

    private RowCounts rowCounts(UUID modelId) {
        return new RowCounts(count("SELECT count(*) FROM diagrammer.classifier WHERE model_id = ?", modelId),
                count("SELECT count(*) FROM diagrammer.uml_relationship WHERE model_id = ?", modelId),
                count("SELECT count(*) FROM diagrammer.element_geometry g JOIN diagrammer.diagram_view v "
                        + "ON v.view_id = g.view_id WHERE v.model_id = ?", modelId),
                count("SELECT count(*) FROM diagrammer.view_style s JOIN diagrammer.diagram_view v "
                        + "ON v.view_id = s.view_id WHERE v.model_id = ?", modelId));
    }

    private int count(String sql, UUID modelId) {
        return jdbcTemplate.queryForObject(sql, Integer.class, modelId);
    }

    private Set<UUID> elementIds(SnapshotModel snapshot) {
        Set<UUID> ids = new HashSet<>();
        snapshot.views().forEach(view -> {
            ids.add(view.id());
            view.elementGeometry().forEach(geometry -> ids.add(geometry.id()));
            view.connectorPath().forEach(path -> ids.add(path.id()));
        });
        snapshot.classifiers().forEach(classifier -> {
            ids.add(classifier.id());
            classifier.attributes().forEach(attribute -> ids.add(attribute.id()));
            classifier.operations().forEach(operation -> {
                ids.add(operation.id());
                operation.parameters().forEach(parameter -> ids.add(parameter.id()));
            });
            classifier.constraints().forEach(constraint -> ids.add(constraint.id()));
        });
        snapshot.relationships().forEach(relationship -> {
            ids.add(relationship.id());
            relationship.ends().forEach(end -> ids.add(end.id()));
            relationship.constraints().forEach(constraint -> ids.add(constraint.id()));
        });
        return ids;
    }

    private record Fixture(UmlModel model, UUID mutableClassifierId) {
    }

    private record RowCounts(int classifiers, int relationships, int geometries, int viewStyles) {
    }
}
