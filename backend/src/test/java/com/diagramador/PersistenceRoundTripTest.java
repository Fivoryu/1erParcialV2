package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.domain.Classifier;
import com.diagramador.domain.ConnectorPath;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.ElementGeometry;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.RelationshipEnd;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.domain.UmlConstraint;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UmlOperation;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.domain.UserAccount;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.SeverityKind;
import com.diagramador.domain.TargetType;
import com.diagramador.persistence.ClassifierRepository;
import com.diagramador.persistence.ConnectorPathRepository;
import com.diagramador.persistence.DiagramViewRepository;
import com.diagramador.persistence.ElementGeometryRepository;
import com.diagramador.persistence.OperationParameterRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.RelationshipEndRepository;
import com.diagramador.persistence.TypeReferenceRepository;
import com.diagramador.persistence.UmlAttributeRepository;
import com.diagramador.persistence.UmlConstraintRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UmlOperationRepository;
import com.diagramador.persistence.UmlRelationshipRepository;
import com.diagramador.persistence.UserAccountRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F1: writes a model graph, releases the persistence context, reads it back in a fresh
 * transaction and asserts the graph is identical, including native enum values and citext.
 */
@SpringBootTest
class PersistenceRoundTripTest extends AbstractPostgresIntegrationTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final UserAccountRepository userAccountRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UmlModelRepository umlModelRepository;
    private final ClassifierRepository classifierRepository;
    private final TypeReferenceRepository typeReferenceRepository;
    private final UmlAttributeRepository umlAttributeRepository;
    private final UmlOperationRepository umlOperationRepository;
    private final OperationParameterRepository operationParameterRepository;
    private final UmlRelationshipRepository umlRelationshipRepository;
    private final RelationshipEndRepository relationshipEndRepository;
    private final UmlConstraintRepository umlConstraintRepository;
    private final DiagramViewRepository diagramViewRepository;
    private final ElementGeometryRepository elementGeometryRepository;
    private final ConnectorPathRepository connectorPathRepository;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    PersistenceRoundTripTest(UserAccountRepository userAccountRepository, ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository, UmlModelRepository umlModelRepository,
            ClassifierRepository classifierRepository, TypeReferenceRepository typeReferenceRepository,
            UmlAttributeRepository umlAttributeRepository, UmlOperationRepository umlOperationRepository,
            OperationParameterRepository operationParameterRepository,
            UmlRelationshipRepository umlRelationshipRepository,
            RelationshipEndRepository relationshipEndRepository, UmlConstraintRepository umlConstraintRepository,
            DiagramViewRepository diagramViewRepository, ElementGeometryRepository elementGeometryRepository,
            ConnectorPathRepository connectorPathRepository, TransactionTemplate transactionTemplate,
            JdbcTemplate jdbcTemplate) {
        this.userAccountRepository = userAccountRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.umlModelRepository = umlModelRepository;
        this.classifierRepository = classifierRepository;
        this.typeReferenceRepository = typeReferenceRepository;
        this.umlAttributeRepository = umlAttributeRepository;
        this.umlOperationRepository = umlOperationRepository;
        this.operationParameterRepository = operationParameterRepository;
        this.umlRelationshipRepository = umlRelationshipRepository;
        this.relationshipEndRepository = relationshipEndRepository;
        this.umlConstraintRepository = umlConstraintRepository;
        this.diagramViewRepository = diagramViewRepository;
        this.elementGeometryRepository = elementGeometryRepository;
        this.connectorPathRepository = connectorPathRepository;
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void modelGraphSurvivesAFreshPersistenceContext() {
        WrittenGraph written = writeGraph();
        assertGraphReadBack(written);
    }

    @Test
    void citextColumnComparesCaseInsensitively() {
        WrittenGraph written = writeGraph();
        Integer matches = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM diagrammer.user_account WHERE email = cast(? AS citext)",
                Integer.class, written.memberEmail().toUpperCase());
        assertThat(matches).isEqualTo(1);
    }

    private WrittenGraph writeGraph() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return transactionTemplate.execute(status -> {
            UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID)
                    .orElseThrow(() -> new IllegalStateException("V2__seed_owner.sql did not create the owner row"));
            UserAccount member = userAccountRepository
                    .save(UserAccount.create("t1-member-" + tag, "T1 Member", "Member." + tag + "@Example.COM"));

            Project project = projectRepository.save(
                    Project.create(owner, "Increment 1", "round-trip project"));
            UmlModel model = umlModelRepository.save(UmlModel.create(project, "Diagrama de clases"));
            projectMemberRepository.save(ProjectMember.create(project, member, ProjectMemberRole.EDITOR));

            Classifier user = classifierRepository.save(
                    Classifier.create(model, null, ClassifierKind.CLASS, "Usuario", false));
            Classifier base = classifierRepository.save(
                    Classifier.create(model, null, ClassifierKind.ABSTRACT_CLASS, "BaseEntity", true));

            TypeReference textType = typeReferenceRepository.save(TypeReference.forPrimitive(model, "String"));

            UmlAttribute attribute = umlAttributeRepository.save(UmlAttribute.create(user, textType, "nombre",
                    "ATTRIBUTE", "PRIVATE", MultiplicityKind.EXACTLY_ONE, "'sin nombre'", 0));

            UmlOperation operation = umlOperationRepository.save(
                    UmlOperation.create(user, "autenticar", null, "PUBLIC", false, true, 0));
            OperationParameter parameter = operationParameterRepository.save(
                    OperationParameter.create(operation, textType, "credencial", "IN", 0));

            UmlRelationship relationship = umlRelationshipRepository.save(
                    UmlRelationship.create(model, RelationshipKind.AGGREGATION, "compone"));
            RelationshipEnd firstEnd = relationshipEndRepository.save(RelationshipEnd.create(relationship, user,
                    (short) 1, "owner", MultiplicityKind.EXACTLY_ONE, NavigationKind.NAVIGABLE, "SHARED"));
            RelationshipEnd secondEnd = relationshipEndRepository.save(RelationshipEnd.create(relationship, base,
                    (short) 2, null, MultiplicityKind.ZERO_OR_MANY, NavigationKind.NOT_NAVIGABLE, "NONE"));

            UmlConstraint constraint = umlConstraintRepository.save(UmlConstraint.create(model, TargetType.CLASSIFIER,
                    user.getId(), "self.owner <> null", SeverityKind.ERROR));

            DiagramView view = diagramViewRepository.save(DiagramView.create(model, "Vista principal", "CLASS", true));
            ElementGeometry geometry = elementGeometryRepository.save(ElementGeometry.create(view,
                    TargetType.CLASSIFIER, user.getId(), 40, 60, 180, 90, 2));
            ConnectorPath path = connectorPathRepository
                    .save(ConnectorPath.create(view, relationship, 0, 120, 140));

            return new WrittenGraph(project.getId(), model.getId(), user.getId(),
                    base.getId(), attribute.getId(), operation.getId(),
                    parameter.getId(), relationship.getId(), firstEnd.getId(),
                    secondEnd.getId(), constraint.getId(), view.getId(),
                    geometry.getId(), path.getId(), member.getId(), member.getEmail());
        });
    }

    private void assertGraphReadBack(WrittenGraph written) {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.flush();
            entityManager.clear();

            Project project = projectRepository.findById(written.projectId()).orElseThrow();
            assertThat(project.getName()).isEqualTo("Increment 1");
            assertThat(project.getDescription()).isEqualTo("round-trip project");
            assertThat(project.getOwner().getId()).isEqualTo(SEED_OWNER_ID);
            assertThat(project.getCreatedAt()).isNotNull();

            UmlModel model = umlModelRepository.findByProjectId(written.projectId()).orElseThrow();
            assertThat(model.getId()).isEqualTo(written.modelId());
            assertThat(model.getName()).isEqualTo("Diagrama de clases");
            assertThat(model.getCurrentRevision()).isZero();

            List<ProjectMember> members = projectMemberRepository.findByProjectId(written.projectId());
            assertThat(members).hasSize(1);
            assertThat(members.getFirst().getRole()).isEqualTo(ProjectMemberRole.EDITOR);
            assertThat(members.getFirst().getStatus()).isEqualTo("ACTIVE");
            assertThat(members.getFirst().getUser().getId()).isEqualTo(written.memberUserId());

            List<Classifier> classifiers = classifierRepository.findByModelId(written.modelId());
            assertThat(classifiers).hasSize(2);
            assertThat(classifiers).allSatisfy(classifier -> assertThat(classifier.getKind()).isIn(
                    ClassifierKind.CLASS, ClassifierKind.ABSTRACT_CLASS));
            Classifier user = classifierRepository.findById(written.classifierAId()).orElseThrow();
            assertThat(user.getKind()).isEqualTo(ClassifierKind.CLASS);
            assertThat(user.getName()).isEqualTo("Usuario");
            assertThat(user.isAbstract()).isFalse();
            assertThat(user.getNamespace()).isNull();
            Classifier base = classifierRepository.findById(written.classifierBId()).orElseThrow();
            assertThat(base.getKind()).isEqualTo(ClassifierKind.ABSTRACT_CLASS);
            assertThat(base.isAbstract()).isTrue();

            List<UmlAttribute> attributes = umlAttributeRepository.findByClassifierId(written.classifierAId());
            assertThat(attributes).hasSize(1);
            UmlAttribute attribute = attributes.getFirst();
            assertThat(attribute.getId()).isEqualTo(written.attributeId());
            assertThat(attribute.getName()).isEqualTo("nombre");
            assertThat(attribute.getKind()).isEqualTo("ATTRIBUTE");
            assertThat(attribute.getVisibility()).isEqualTo("PRIVATE");
            assertThat(attribute.getMultiplicity()).isEqualTo(MultiplicityKind.EXACTLY_ONE);
            assertThat(attribute.getDefaultValue()).isEqualTo("'sin nombre'");
            assertThat(attribute.getOrdinal()).isZero();
            assertThat(attribute.getTypeReference().getPrimitiveName()).isEqualTo("String");

            List<UmlOperation> operations = umlOperationRepository.findByClassifierId(written.classifierAId());
            assertThat(operations).hasSize(1);
            assertThat(operations.getFirst().getName()).isEqualTo("autenticar");
            assertThat(operations.getFirst().isStatic()).isTrue();
            assertThat(operations.getFirst().isAbstract()).isFalse();
            assertThat(operations.getFirst().getVisibility()).isEqualTo("PUBLIC");

            List<OperationParameter> parameters = operationParameterRepository
                    .findByOperationId(written.operationId());
            assertThat(parameters).hasSize(1);
            assertThat(parameters.getFirst().getId()).isEqualTo(written.parameterId());
            assertThat(parameters.getFirst().getName()).isEqualTo("credencial");
            assertThat(parameters.getFirst().getDirection()).isEqualTo("IN");
            assertThat(parameters.getFirst().getTypeReference().getPrimitiveName()).isEqualTo("String");

            List<UmlRelationship> relationships = umlRelationshipRepository.findByModelId(written.modelId());
            assertThat(relationships).hasSize(1);
            assertThat(relationships.getFirst().getKind()).isEqualTo(RelationshipKind.AGGREGATION);
            assertThat(relationships.getFirst().getName()).isEqualTo("compone");

            List<RelationshipEnd> ends = relationshipEndRepository
                    .findByRelationshipId(written.relationshipId()).stream()
                    .sorted(Comparator.comparingInt(RelationshipEnd::getOrdinal))
                    .toList();
            assertThat(ends).hasSize(2);
            assertThat(ends.get(0).getId()).isEqualTo(written.firstEndId());
            assertThat(ends.get(0).getOrdinal()).isEqualTo((short) 1);
            assertThat(ends.get(0).getRole()).isEqualTo("owner");
            assertThat(ends.get(0).getMultiplicity()).isEqualTo(MultiplicityKind.EXACTLY_ONE);
            assertThat(ends.get(0).getNavigability()).isEqualTo(NavigationKind.NAVIGABLE);
            assertThat(ends.get(0).getAggregationKind()).isEqualTo("SHARED");
            assertThat(ends.get(0).getClassifier().getId()).isEqualTo(written.classifierAId());
            assertThat(ends.get(1).getId()).isEqualTo(written.secondEndId());
            assertThat(ends.get(1).getOrdinal()).isEqualTo((short) 2);
            assertThat(ends.get(1).getRole()).isNull();
            assertThat(ends.get(1).getMultiplicity()).isEqualTo(MultiplicityKind.ZERO_OR_MANY);
            assertThat(ends.get(1).getNavigability()).isEqualTo(NavigationKind.NOT_NAVIGABLE);
            assertThat(ends.get(1).getAggregationKind()).isEqualTo("NONE");
            assertThat(ends.get(1).getClassifier().getId()).isEqualTo(written.classifierBId());

            List<UmlConstraint> constraints = umlConstraintRepository.findByModelId(written.modelId());
            assertThat(constraints).hasSize(1);
            assertThat(constraints.getFirst().getId()).isEqualTo(written.constraintId());
            assertThat(constraints.getFirst().getOwnerType()).isEqualTo(TargetType.CLASSIFIER);
            assertThat(constraints.getFirst().getOwnerId()).isEqualTo(written.classifierAId());
            assertThat(constraints.getFirst().getExpression()).isEqualTo("self.owner <> null");
            assertThat(constraints.getFirst().getSeverity()).isEqualTo(SeverityKind.ERROR);

            List<DiagramView> views = diagramViewRepository.findByModelId(written.modelId());
            assertThat(views).hasSize(1);
            assertThat(views.getFirst().getName()).isEqualTo("Vista principal");
            assertThat(views.getFirst().getViewKind()).isEqualTo("CLASS");
            assertThat(views.getFirst().isDefault()).isTrue();

            List<ElementGeometry> geometries = elementGeometryRepository.findByViewId(written.viewId());
            assertThat(geometries).hasSize(1);
            ElementGeometry geometry = geometries.getFirst();
            assertThat(geometry.getId()).isEqualTo(written.geometryId());
            assertThat(geometry.getTargetType()).isEqualTo(TargetType.CLASSIFIER);
            assertThat(geometry.getTargetId()).isEqualTo(written.classifierAId());
            assertThat(geometry.getX()).isEqualTo(40);
            assertThat(geometry.getY()).isEqualTo(60);
            assertThat(geometry.getW()).isEqualTo(180);
            assertThat(geometry.getH()).isEqualTo(90);
            assertThat(geometry.getZOrder()).isEqualTo(2);

            List<ConnectorPath> paths = connectorPathRepository.findByViewId(written.viewId());
            assertThat(paths).hasSize(1);
            assertThat(paths.getFirst().getId()).isEqualTo(written.pathId());
            assertThat(paths.getFirst().getOrdinal()).isZero();
            assertThat(paths.getFirst().getX()).isEqualTo(120);
            assertThat(paths.getFirst().getY()).isEqualTo(140);

            UserAccount member = userAccountRepository.findById(written.memberUserId()).orElseThrow();
            assertThat(member.getDisplayName()).isEqualTo("T1 Member");
            assertThat(member.getStatus()).isEqualTo("ACTIVE");
            assertThat(member.getEmail()).isEqualTo(written.memberEmail());
            assertThat(member.getRevokedAt()).isNull();
        });
    }

    private record WrittenGraph(UUID projectId, UUID modelId, UUID classifierAId, UUID classifierBId,
            UUID attributeId, UUID operationId, UUID parameterId, UUID relationshipId, UUID firstEndId,
            UUID secondEndId, UUID constraintId, UUID viewId, UUID geometryId, UUID pathId, UUID memberUserId,
            String memberEmail) {
    }
}
