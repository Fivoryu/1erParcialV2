package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.SeverityKind;
import com.diagramador.domain.TargetType;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * F2: proves that Flyway owns the schema. The tables and columns the entities map exist in the
 * {@code diagrammer} schema, the native enum types are native, citext is citext, created_at is
 * a timestamp with time zone, and no table reserved for a later task exists yet.
 */
@SpringBootTest
class SchemaOwnershipTest extends AbstractPostgresIntegrationTest {

    private static final Map<String, Set<String>> MAPPED_COLUMNS = Map.ofEntries(
            Map.entry("user_account", Set.of("user_id", "external_subject", "display_name", "email", "status",
                    "revoked_at", "created_at", "updated_at")),
            Map.entry("project", Set.of("project_id", "owner_user_id", "name", "description", "archived_at",
                    "created_at", "updated_at")),
            Map.entry("project_member", Set.of("project_id", "user_id", "role", "status", "joined_at", "updated_at")),
            Map.entry("uml_model", Set.of("model_id", "project_id", "name", "current_revision", "created_at")),
            Map.entry("model_version", Set.of("version_id", "model_id", "revision", "parent_revision", "state_hash",
                    "validation_state", "created_by_user_id", "created_at")),
            Map.entry("model_command", Set.of("command_id", "model_id", "author_user_id", "base_revision",
                    "result_revision", "operation", "target_type", "target_id", "payload", "origin",
                    "authorization_classification", "inverse_of_command_id", "causal_parent_operation_id",
                    "idempotency_key", "created_at")),
            Map.entry("cascade_group", Set.of("cascade_id", "model_id", "root_command_id", "element_count", "restored_at")),
            Map.entry("tombstone", Set.of("tombstone_id", "model_id", "target_type", "target_id", "cascade_id",
                    "command_id", "deleted_at")),
            Map.entry("classifier",  Set.of("classifier_id", "model_id", "namespace_id", "kind", "name", "is_abstract",
                    "created_at")),
            Map.entry("type_reference", Set.of("type_reference_id", "model_id", "classifier_id", "primitive_name",
                    "created_at")),
            Map.entry("uml_attribute", Set.of("attribute_id", "classifier_id", "type_reference_id", "name", "kind",
                    "visibility", "multiplicity", "default_value", "ordinal", "created_at")),
            Map.entry("uml_operation", Set.of("operation_id", "classifier_id", "name", "return_type_reference_id",
                    "visibility", "is_abstract", "is_static", "ordinal", "created_at")),
            Map.entry("operation_parameter", Set.of("parameter_id", "operation_id", "type_reference_id", "name",
                    "direction", "ordinal")),
            Map.entry("uml_relationship", Set.of("relationship_id", "model_id", "kind", "name", "created_at")),
            Map.entry("relationship_end", Set.of("relationship_end_id", "relationship_id", "classifier_id", "ordinal",
                    "role", "multiplicity", "navigability", "aggregation_kind")),
            Map.entry("uml_constraint", Set.of("constraint_id", "model_id", "owner_type", "owner_id", "expression",
                    "severity", "created_at")),
            Map.entry("diagram_view", Set.of("view_id", "model_id", "name", "view_kind", "is_default", "created_at")),
            Map.entry("element_geometry", Set.of("geometry_id", "view_id", "target_type", "target_id", "x", "y", "w",
                    "h", "z_order")),
            Map.entry("connector_path", Set.of("path_id", "view_id", "relationship_id", "ordinal", "x", "y")));

    private static final Set<String> TABLES_RESERVED_FOR_LATER_TASKS = Set.of(
            "invitation", "model_conflict",
            "name_collision", "ai_proposal", "detected_element", "xmi_exchange", "xmi_loss_item",
            "generation_target", "generation_profile", "generation_run", "template_version",
            "generated_artifact", "verification_result", "audit_log");

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    SchemaOwnershipTest(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void flywayAppliedBothMigrations() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT version, description, success FROM diagrammer.flyway_schema_history ORDER BY installed_rank");

        assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
        assertThat(history).extracting(row -> String.valueOf(row.get("description")))
                .contains("baseline", "seed owner");
        assertThat(history).extracting(row -> String.valueOf(row.get("version")))
                .contains("1", "2", "3");
    }

    @Test
    void schemaOwnsExactlyTheTablesOfThisIncrement() {
        Set<String> tables = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'diagrammer'",
                String.class));

        Set<String> expectedTables = new HashSet<>(MAPPED_COLUMNS.keySet());
        expectedTables.add("flyway_schema_history");

        assertThat(tables).containsExactlyInAnyOrderElementsOf(expectedTables);
        assertThat(tables).doesNotContainAnyElementsOf(TABLES_RESERVED_FOR_LATER_TASKS);
    }

    @Test
    void everyMappedColumnExistsInItsTable() {
        for (Map.Entry<String, Set<String>> entry : MAPPED_COLUMNS.entrySet()) {
            Set<String> columns = Set.copyOf(jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = 'diagrammer' AND table_name = ?",
                    String.class, entry.getKey()));

            assertThat(columns).as("columns of diagrammer.%s", entry.getKey())
                    .containsExactlyInAnyOrderElementsOf(entry.getValue());
        }
    }

    @Test
    void nativeEnumColumnsUseTheirPostgresType() {
        assertColumnType("classifier", "kind", "classifier_kind");
        assertColumnType("uml_attribute", "multiplicity", "multiplicity_kind");
        assertColumnType("relationship_end", "multiplicity", "multiplicity_kind");
        assertColumnType("relationship_end", "navigability", "navigation_kind");
        assertColumnType("project_member", "role", "project_member_role");
        assertColumnType("uml_relationship", "kind", "relationship_kind");
        assertColumnType("uml_constraint", "owner_type", "target_type");
        assertColumnType("uml_constraint", "severity", "severity_kind");
        assertColumnType("element_geometry", "target_type", "target_type");
        assertColumnType("model_version", "validation_state", "validation_state");
        assertColumnType("model_command", "origin", "command_origin");
        assertColumnType("model_command", "authorization_classification", "authorization_classification");
    }

    @Test
    void javaEnumsAndPostgresEnumsHaveTheSameLabels() {
        assertEnumLabels("classifier_kind", ClassifierKind.class, 5);
        assertEnumLabels("multiplicity_kind", MultiplicityKind.class, 5);
        assertEnumLabels("navigation_kind", NavigationKind.class, 3);
        assertEnumLabels("project_member_role", ProjectMemberRole.class, 3);
        assertEnumLabels("relationship_kind", RelationshipKind.class, 6);
        assertEnumLabels("severity_kind", SeverityKind.class, 3);
        assertEnumLabels("target_type", TargetType.class, 8);
        assertEnumLabels("validation_state", com.diagramador.command.ValidationState.class, 3);
        assertEnumLabels("command_origin", com.diagramador.command.CommandOrigin.class, 11);
        assertEnumLabels("authorization_classification", com.diagramador.command.AuthorizationClassification.class, 5);
    }

    @Test
    void citextAndTimestamptzKeepTheirPhysicalType() {
        assertColumnType("user_account", "email", "citext");

        List<String> dataTypes = jdbcTemplate.queryForList(
                "SELECT data_type FROM information_schema.columns WHERE table_schema = 'diagrammer' AND table_name = 'user_account' AND column_name = 'created_at'",
                String.class);
        assertThat(dataTypes).containsExactly("timestamp with time zone");
    }

    private void assertColumnType(String table, String column, String expectedUdtName) {
        List<String> udtNames = jdbcTemplate.queryForList(
                "SELECT udt_name FROM information_schema.columns WHERE table_schema = 'diagrammer' AND table_name = ? AND column_name = ?",
                String.class, table, column);

        assertThat(udtNames).as("physical type of diagrammer.%s.%s", table, column)
                .containsExactly(expectedUdtName);
    }

    private void assertEnumLabels(String sqlTypeName, Class<? extends Enum<?>> javaEnum, int expectedLabelCount) {
        List<String> labels = jdbcTemplate.queryForList(
                "SELECT e.enumlabel FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid WHERE t.typname = ? ORDER BY e.enumsortorder",
                String.class, sqlTypeName);
        List<String> javaNames = Arrays.stream(javaEnum.getEnumConstants()).map(Enum::name).toList();

        assertThat(labels).as("labels of the %s type", sqlTypeName).hasSize(expectedLabelCount).isEqualTo(javaNames);
    }
}
