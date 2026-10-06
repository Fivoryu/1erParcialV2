package com.diagramador.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.domain.DiagramView;
import com.diagramador.domain.Project;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.persistence.DiagramViewRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UserAccountRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * IA4-07: the versioned style.set command persists one view_style row per (view, relationship)
 * through the normal command pipeline: payload whitelist, routing whitelist, view/target scope
 * validation and idempotent replay.
 */
@SpringBootTest
class StyleSetHandlerTest {

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

    @Autowired
    private CommandService commandService;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectMemberRepository projectMemberRepository;
    @Autowired
    private UmlModelRepository modelRepository;
    @Autowired
    private DiagramViewRepository viewRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void styleSetUpsertsOneRowPerViewAndTargetWithServerDefaults() {
        Fixture fixture = fixture("style-defaults");
        UUID relationshipId = createRelationship(fixture);

        CommandResult result = command(fixture, 3, Map.of(
                "viewId", fixture.view().getId().toString(),
                "targetId", relationshipId.toString(),
                "routing", "ORTHOGONAL"));

        assertAccepted(result);
        assertThat(result.resultRevision()).isEqualTo(4L);
        assertThat(styleRows(fixture)).singleElement().satisfies(row -> {
            assertThat(row.get("routing")).isEqualTo("ORTHOGONAL");
            assertThat(row.get("corner_radius")).isEqualTo(0);
            assertThat(row.get("stroke_width")).isEqualTo(2);
            assertThat(row.get("dashed")).isEqualTo(false);
            assertThat(((UUID) row.get("view_id")).toString()).isEqualTo(fixture.view().getId().toString());
            assertThat(((UUID) row.get("target_id")).toString()).isEqualTo(relationshipId.toString());
        });
    }

    @Test
    void styleSetDefaultsRoutingToStraightWhenTheFieldIsAbsent() {
        Fixture fixture = fixture("style-default-routing");
        UUID relationshipId = createRelationship(fixture);

        assertAccepted(command(fixture, 3, Map.of(
                "viewId", fixture.view().getId().toString(),
                "targetId", relationshipId.toString(),
                "dashed", true)));

        assertThat(styleRows(fixture)).singleElement().satisfies(row -> {
            assertThat(row.get("routing")).isEqualTo("STRAIGHT");
            assertThat(row.get("dashed")).isEqualTo(true);
        });
    }

    @Test
    void styleSetUpdatesTheExistingRowInsteadOfAddingOne() {
        Fixture fixture = fixture("style-upsert");
        UUID relationshipId = createRelationship(fixture);
        assertAccepted(command(fixture, 3, Map.of(
                "viewId", fixture.view().getId().toString(),
                "targetId", relationshipId.toString(),
                "routing", "ORTHOGONAL", "cornerRadius", 12, "strokeWidth", 3, "dashed", true)));

        CommandResult second = command(fixture, 4, Map.of(
                "viewId", fixture.view().getId().toString(),
                "targetId", relationshipId.toString(),
                "routing", "FREE", "cornerRadius", 0, "strokeWidth", 2, "dashed", false));

        assertAccepted(second);
        assertThat(second.resultRevision()).isEqualTo(5L);
        assertThat(styleRows(fixture)).hasSize(1).satisfies(rows -> {
            assertThat(rows.get(0).get("routing")).isEqualTo("FREE");
            assertThat(rows.get(0).get("corner_radius")).isEqualTo(0);
            assertThat(rows.get(0).get("stroke_width")).isEqualTo(2);
            assertThat(rows.get(0).get("dashed")).isEqualTo(false);
        });
    }

    @Test
    void styleSetRejectsPayloadFieldsOutsideTheWhitelist() {
        Fixture fixture = fixture("style-whitelist");
        UUID relationshipId = createRelationship(fixture);

        CommandResult result = command(fixture, 3, Map.of(
                "viewId", fixture.view().getId().toString(),
                "targetId", relationshipId.toString(),
                "routing", "ORTHOGONAL",
                "color", "#ff0000"));

        assertRejected(result, ErrorCode.INVALID_PAYLOAD, "unsupported field");
        assertThat(styleRows(fixture)).isEmpty();
        assertThat(currentRevision(fixture)).isEqualTo(3L);
    }

    @Test
    void styleSetRejectsRoutingValuesOutsideTheModeWhitelist() {
        Fixture fixture = fixture("style-routing");
        UUID relationshipId = createRelationship(fixture);

        CommandResult result = command(fixture, 3, Map.of(
                "viewId", fixture.view().getId().toString(),
                "targetId", relationshipId.toString(),
                "routing", "DIAGONAL"));

        assertRejected(result, ErrorCode.INVALID_PAYLOAD, "routing");
        assertThat(styleRows(fixture)).isEmpty();
    }

    @Test
    void styleSetRejectsNegativeCornerRadiusAndStrokeWidth() {
        Fixture fixture = fixture("style-negative");
        UUID relationshipId = createRelationship(fixture);

        CommandResult negativeRadius = command(fixture, 3, Map.of(
                "viewId", fixture.view().getId().toString(),
                "targetId", relationshipId.toString(),
                "routing", "ORTHOGONAL", "cornerRadius", -1));
        CommandResult zeroWidth = command(fixture, 3, Map.of(
                "viewId", fixture.view().getId().toString(),
                "targetId", relationshipId.toString(),
                "routing", "ORTHOGONAL", "strokeWidth", 0));

        assertRejected(negativeRadius, ErrorCode.INVALID_PAYLOAD, "cornerRadius");
        assertRejected(zeroWidth, ErrorCode.INVALID_PAYLOAD, "strokeWidth");
        assertThat(styleRows(fixture)).isEmpty();
    }

    @Test
    void styleSetRejectsAViewThatDoesNotBelongToTheModel() {
        Fixture fixture = fixture("style-view-scope");
        Fixture other = fixture("style-view-scope-other");
        UUID relationshipId = createRelationship(fixture);

        CommandResult unknownView = command(fixture, 3, Map.of(
                "viewId", UUID.randomUUID().toString(),
                "targetId", relationshipId.toString(),
                "routing", "ORTHOGONAL"));
        CommandResult foreignView = command(fixture, 3, Map.of(
                "viewId", other.view().getId().toString(),
                "targetId", relationshipId.toString(),
                "routing", "ORTHOGONAL"));

        assertRejected(unknownView, ErrorCode.PRECONDITION_FAILED, "does not belong to model");
        assertRejected(foreignView, ErrorCode.PRECONDITION_FAILED, "does not belong to model");
        assertThat(styleRows(fixture)).isEmpty();
        assertThat(styleRows(other)).isEmpty();
    }

    @Test
    void styleSetRejectsARelationshipFromAnotherModel() {
        Fixture fixture = fixture("style-target-scope");
        Fixture other = fixture("style-target-scope-other");
        UUID foreignRelationship = createRelationship(other);

        CommandResult result = command(fixture, 0, Map.of(
                "viewId", fixture.view().getId().toString(),
                "targetId", foreignRelationship.toString(),
                "routing", "ORTHOGONAL"));

        assertRejected(result, ErrorCode.PRECONDITION_FAILED, "does not belong to model");
        assertThat(styleRows(fixture)).isEmpty();
    }

    @Test
    void styleSetReplayWithTheSameIdempotencyKeyReturnsTheOriginalResultWithoutBumpingTheRevision() {
        Fixture fixture = fixture("style-idempotent");
        UUID relationshipId = createRelationship(fixture);
        UUID idempotencyKey = UUID.randomUUID();
        CommandResult first = command(fixture, 3, Map.of(
                "viewId", fixture.view().getId().toString(),
                "targetId", relationshipId.toString(),
                "routing", "ORTHOGONAL"), idempotencyKey);
        assertAccepted(first);

        CommandResult replay = command(fixture, 3, Map.of(
                "viewId", fixture.view().getId().toString(),
                "targetId", relationshipId.toString(),
                "routing", "ORTHOGONAL"), idempotencyKey);

        assertAccepted(replay);
        assertThat(replay.resultRevision()).isEqualTo(first.resultRevision());
        assertThat(currentRevision(fixture)).isEqualTo(4L);
        assertThat(styleRows(fixture)).hasSize(1);
    }

    private List<Map<String, Object>> styleRows(Fixture fixture) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM diagrammer.view_style WHERE view_id = ? ORDER BY target_id",
                fixture.view().getId());
    }

    /** One field of the persisted ROR-02 adjustment, read as jsonb text; null when the column is null. */
    private String styleAdjustment(Fixture fixture, String field) {
        String extraction = field.equals("axis") ? "->>" : "->";
        return jdbcTemplate.queryForObject(
                "SELECT route_adjustment" + extraction + "'" + field + "' FROM diagrammer.view_style WHERE view_id = ?",
                String.class, fixture.view().getId());
    }

    @Test
    void styleSetPersistsTheOrthogonalRouteAdjustmentAndReplaysItThroughTheRow() {
        Fixture fixture = fixture("style-adjustment");
        UUID relationshipId = createRelationship(fixture);

        assertAccepted(command(fixture, 3, Map.of(
                "viewId", fixture.view().getId().toString(),
                "targetId", relationshipId.toString(),
                "routing", "ORTHOGONAL",
                "routeAdjustment", Map.of(
                        "axis", "H",
                        "corridors", List.of(40, -30),
                        "levels", List.of(-12)))));

        assertThat(styleRows(fixture)).singleElement().satisfies(row -> {
            assertThat(row.get("routing")).isEqualTo("ORTHOGONAL");
            assertThat(row.get("route_adjustment")).isNotNull();
        });
        assertThat(styleAdjustment(fixture, "axis")).isEqualTo("H");
        assertThat(styleAdjustment(fixture, "corridors")).isEqualTo("[40, -30]");
        assertThat(styleAdjustment(fixture, "levels")).isEqualTo("[-12]");
    }

    @Test
    void styleSetClearsTheRouteAdjustmentWhenTheFieldIsJsonNull() {
        Fixture fixture = fixture("style-adjustment-clear");
        UUID relationshipId = createRelationship(fixture);
        Map<String, Object> withAdjustment = new java.util.HashMap<>();
        withAdjustment.put("viewId", fixture.view().getId().toString());
        withAdjustment.put("targetId", relationshipId.toString());
        withAdjustment.put("routing", "ORTHOGONAL");
        withAdjustment.put("routeAdjustment", Map.of(
                "axis", "H", "corridors", List.of(40, -30), "levels", List.of(-12)));
        assertAccepted(command(fixture, 3, withAdjustment));
        assertThat(styleAdjustment(fixture, "axis")).isEqualTo("H");

        Map<String, Object> cleared = new java.util.HashMap<>(withAdjustment);
        cleared.put("routeAdjustment", null);
        assertAccepted(command(fixture, 4, cleared));

        assertThat(styleAdjustment(fixture, "axis")).isNull();
        assertThat(styleAdjustment(fixture, "corridors")).isNull();
        assertThat(styleAdjustment(fixture, "levels")).isNull();
    }

    @Test
    void styleSetRejectsMalformedRouteAdjustments() {
        Fixture fixture = fixture("style-adjustment-shape");
        UUID relationshipId = createRelationship(fixture);
        Map<String, Object> base = new java.util.HashMap<>();
        base.put("viewId", fixture.view().getId().toString());
        base.put("targetId", relationshipId.toString());
        base.put("routing", "ORTHOGONAL");

        Map<String, Object> unknownKey = new java.util.HashMap<>(base);
        unknownKey.put("routeAdjustment", Map.of(
                "axis", "H", "corridors", List.of(40, -30), "levels", List.of(-12), "offset", 5));
        Map<String, Object> badAxis = new java.util.HashMap<>(base);
        badAxis.put("routeAdjustment", Map.of(
                "axis", "X", "corridors", List.of(40, -30), "levels", List.of(-12)));
        Map<String, Object> levelMismatch = new java.util.HashMap<>(base);
        levelMismatch.put("routeAdjustment", Map.of(
                "axis", "H", "corridors", List.of(40, -30), "levels", List.of()));
        Map<String, Object> nonInteger = new java.util.HashMap<>(base);
        nonInteger.put("routeAdjustment", Map.of(
                "axis", "H", "corridors", List.of(40.5, -30), "levels", List.of(-12)));
        Map<String, Object> outOfRange = new java.util.HashMap<>(base);
        outOfRange.put("routeAdjustment", Map.of(
                "axis", "H", "corridors", List.of(100001, -30), "levels", List.of(-12)));

        for (Map<String, Object> payload : List.of(unknownKey, badAxis, levelMismatch, nonInteger, outOfRange)) {
            assertRejected(command(fixture, 3, payload), ErrorCode.INVALID_PAYLOAD, "routeAdjustment");
        }
        assertThat(styleRows(fixture)).isEmpty();
        assertThat(currentRevision(fixture)).isEqualTo(3L);
    }

    @Test
    void styleSetRejectsARouteAdjustmentWithoutOrthogonalRouting() {
        Fixture fixture = fixture("style-adjustment-routing");
        UUID relationshipId = createRelationship(fixture);

        CommandResult result = command(fixture, 3, Map.of(
                "viewId", fixture.view().getId().toString(),
                "targetId", relationshipId.toString(),
                "routing", "FREE",
                "routeAdjustment", Map.of(
                        "axis", "H", "corridors", List.of(40, -30), "levels", List.of(-12))));

        assertRejected(result, ErrorCode.INVALID_PAYLOAD, "routeAdjustment");
        assertThat(styleRows(fixture)).isEmpty();
    }

    private long currentRevision(Fixture fixture) {
        return modelRepository.findById(fixture.model().getId()).orElseThrow().getCurrentRevision();
    }

    private UUID createRelationship(Fixture fixture) {
        UUID sourceId = createClassifier(fixture, 0, "Source");
        UUID targetId = createClassifier(fixture, 1, "Target");
        UUID relationshipId = UUID.randomUUID();
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("id", relationshipId.toString());
        payload.put("kind", "ASSOCIATION");
        payload.put("name", null);
        payload.put("source", end(sourceId));
        payload.put("target", end(targetId));
        payload.put("waypoints", List.of());
        CommandResult result = command(fixture, 2, "relationship.create", payload,
                List.of(
                new Target("classifier", sourceId), new Target("classifier", targetId)), null);
        assertAccepted(result);
        return relationshipId;
    }

    private Map<String, Object> end(UUID classifierId) {
        Map<String, Object> end = new java.util.HashMap<>();
        end.put("classifierId", classifierId.toString());
        end.put("role", null);
        end.put("multiplicity", "EXACTLY_ONE");
        end.put("navigability", "UNSPECIFIED");
        return end;
    }

    private UUID createClassifier(Fixture fixture, long revision, String name) {
        UUID id = UUID.randomUUID();
        assertAccepted(command(fixture, revision, "classifier.create", Map.of(
                "id", id.toString(), "name", name, "kind", "CLASS"), List.of(), null));
        return id;
    }

    private CommandResult command(Fixture fixture, long revision, Map<String, Object> payload) {
        return command(fixture, revision, "style.set", payload, List.of(), null);
    }

    private CommandResult command(Fixture fixture, long revision, Map<String, Object> payload,
            UUID idempotencyKey) {
        return command(fixture, revision, "style.set", payload, List.of(), idempotencyKey);
    }

    private CommandResult command(Fixture fixture, long revision, String type, Map<String, Object> payload,
            List<Target> targets, UUID idempotencyKey) {
        return commandService.execute(new OperationEnvelope(UUID.randomUUID(), type, fixture.author(),
                fixture.project().getId(), fixture.model().getId(), revision, targets, List.of(), payload,
                CommandOrigin.MANUAL, null,
                idempotencyKey == null ? UUID.randomUUID().toString() : idempotencyKey.toString()));
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

    private Fixture fixture(String label) {
        return transactionTemplate.execute(status -> {
            UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
            UserAccount author = userAccountRepository.save(
                    UserAccount.create("style-" + UUID.randomUUID(), "Style",
                            "style-" + UUID.randomUUID() + "@example.test"));
            Project project = projectRepository.save(Project.create(owner, "Style " + label, "style.set tests"));
            UmlModel model = modelRepository.save(UmlModel.create(project, "Style Model"));
            projectMemberRepository.save(ProjectMember.create(project, owner, ProjectMemberRole.OWNER));
            projectMemberRepository.save(ProjectMember.create(project, author, ProjectMemberRole.EDITOR));
            DiagramView view = viewRepository.save(DiagramView.create(model, "Main", "CLASS", true));
            return new Fixture(project, model, new Author(author.getId(), "style"), view);
        });
    }

    private record Fixture(Project project, UmlModel model, Author author, DiagramView view) {
    }
}
