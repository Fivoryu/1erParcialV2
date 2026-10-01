package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.DeploymentState;
import com.diagramador.domain.GenerationState;
import com.diagramador.domain.InvitationStatus;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.ProjectMemberRole;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.domain.SeverityKind;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.VerificationState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
            Map.entry("user_account", Set.of("user_id", "external_subject", "username", "password_hash",
                    "display_name", "email", "status", "revoked_at", "created_at", "updated_at")),
            Map.entry("user_session", Set.of("session_id", "user_id", "token_hash", "issued_at", "last_seen_at",
                    "expires_at", "revoked_at")),
            Map.entry("project", Set.of("project_id", "owner_user_id", "name", "description", "archived_at",
                    "created_at", "updated_at")),
            Map.entry("project_member", Set.of("project_id", "user_id", "role", "status", "joined_at", "updated_at")),
            Map.entry("invitation", Set.of("invitation_id", "project_id", "invited_by_user_id", "invitee_user_id",
                    "role", "status", "created_at", "updated_at")),
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
            Map.entry("connector_path", Set.of("path_id", "view_id", "relationship_id", "ordinal", "x", "y")),
            Map.entry("generation_target", Set.of("target_id", "model_id", "target_key", "name", "database_engine",
                    "database_name", "database_schema", "connection_config_ref", "configuration_revision",
                    "created_at", "updated_at")),
            Map.entry("generation_profile", Set.of("profile_id", "project_id", "profile_key", "name", "revision",
                    "configuration", "configuration_hash", "created_at")),
            Map.entry("template_version", Set.of("template_id", "profile_id", "label", "revision", "content_hash",
                    "created_at")),
            Map.entry("generation_run", Set.of("run_id", "model_id", "version_id", "target_id",
                    "target_config_revision", "profile_id", "template_id", "input_snapshot_hash",
                    "artifact_set_hash", "artifact_zip_hash", "artifact_zip_uri", "state", "started_at",
                    "finished_at")),
            Map.entry("generated_artifact", Set.of("artifact_id", "run_id", "path", "kind", "content_hash", "uri",
                    "size_bytes")),
            Map.entry("verification_result", Set.of("verification_id", "run_id", "check_name", "state", "evidence",
                    "evidence_hash", "tool_name", "started_at", "finished_at", "created_at")),
            Map.entry("deployment", Set.of("deployment_id", "run_id", "target_id", "state", "http_host_port",
                    "postgres_host_port", "compose_project", "operator_id", "failure_code", "failure_message",
                    "requested_at", "started_at", "finished_at", "updated_at")));

    private static final Set<String> TABLES_RESERVED_FOR_LATER_TASKS = Set.of(
            "model_conflict",
            "name_collision", "ai_proposal", "detected_element", "xmi_exchange", "xmi_loss_item", "audit_log");

    /** Support indexes declared by the physical schema authority for every migrated table. */
    private static final Map<String, Set<String>> SUPPORT_INDEXES = Map.ofEntries(
            Map.entry("user_account", Set.of()),
            Map.entry("user_session", Set.of("idx_session_user")),
            Map.entry("project", Set.of("idx_project_owner")),
            Map.entry("project_member", Set.of("idx_member_user")),
            Map.entry("invitation", Set.of("idx_invitation_project", "uq_invitation_pending_project_invitee")),
            Map.entry("uml_model", Set.of()),
            Map.entry("model_version", Set.of("idx_version_model_rev")),
            Map.entry("model_command", Set.of("idx_command_model_rev", "idx_command_author")),
            Map.entry("cascade_group", Set.of()),
            Map.entry("tombstone", Set.of("idx_tombstone_model")),
            Map.entry("classifier", Set.of("idx_classifier_model", "idx_classifier_namespace")),
            Map.entry("type_reference", Set.of()),
            Map.entry("uml_attribute", Set.of("idx_attribute_classifier")),
            Map.entry("uml_operation", Set.of("idx_operation_classifier")),
            Map.entry("operation_parameter", Set.of("idx_parameter_operation")),
            Map.entry("uml_relationship", Set.of("idx_relationship_model")),
            Map.entry("relationship_end", Set.of("idx_relationship_end_rel", "idx_relationship_end_cls")),
            Map.entry("uml_constraint", Set.of("idx_constraint_model")),
            Map.entry("diagram_view", Set.of()),
            Map.entry("element_geometry", Set.of()),
            Map.entry("connector_path", Set.of()),
            Map.entry("generation_target", Set.of("idx_generation_target_model")),
            Map.entry("generation_profile", Set.of()),
            Map.entry("template_version", Set.of()),
            Map.entry("generation_run", Set.of("idx_run_model", "idx_run_target")),
            Map.entry("generated_artifact", Set.of("idx_artifact_run")),
            Map.entry("verification_result", Set.of("idx_verification_run")),
            Map.entry("deployment", Set.of("idx_deployment_run", "idx_deployment_state",
                    "uq_deployment_active_target", "uq_deployment_active_http_port",
                    "uq_deployment_active_postgres_port")));

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
                .contains("1", "2", "3", "4", "5", "6", "7", "8");
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
        assertColumnType("invitation", "role", "project_member_role");
        assertColumnType("invitation", "status", "invitation_status");
        assertColumnType("uml_relationship", "kind", "relationship_kind");
        assertColumnType("uml_constraint", "owner_type", "target_type");
        assertColumnType("uml_constraint", "severity", "severity_kind");
        assertColumnType("element_geometry", "target_type", "target_type");
        assertColumnType("model_version", "validation_state", "validation_state");
        assertColumnType("model_command", "origin", "command_origin");
        assertColumnType("model_command", "authorization_classification", "authorization_classification");
        assertColumnType("generation_run", "state", "generation_state");
        assertColumnType("verification_result", "state", "verification_state");
        assertColumnType("deployment", "state", "deployment_state");
    }

    @Test
    void javaEnumsAndPostgresEnumsHaveTheSameLabels() {
        assertEnumLabels("classifier_kind", ClassifierKind.class, 5);
        assertEnumLabels("multiplicity_kind", MultiplicityKind.class, 5);
        assertEnumLabels("navigation_kind", NavigationKind.class, 3);
        assertEnumLabels("project_member_role", ProjectMemberRole.class, 3);
        assertEnumLabels("invitation_status", InvitationStatus.class, 4);
        assertEnumLabels("relationship_kind", RelationshipKind.class, 6);
        assertEnumLabels("severity_kind", SeverityKind.class, 3);
        assertEnumLabels("target_type", TargetType.class, 8);
        assertEnumLabels("validation_state", com.diagramador.command.ValidationState.class, 3);
        assertEnumLabels("command_origin", com.diagramador.command.CommandOrigin.class, 11);
        assertEnumLabels("authorization_classification", com.diagramador.command.AuthorizationClassification.class, 5);
        assertEnumLabels("generation_state", GenerationState.class, 4);
        assertEnumLabels("verification_state", VerificationState.class, 3);
        assertEnumLabels("deployment_state", DeploymentState.class, 6);
    }

    @Test
    void supportIndexesExactlyMatchTheAuthority() {
        for (Map.Entry<String, Set<String>> entry : SUPPORT_INDEXES.entrySet()) {
            Set<String> indexes = Set.copyOf(jdbcTemplate.queryForList(
                    "SELECT index_relation.relname "
                            + "FROM pg_class table_relation "
                            + "JOIN pg_namespace table_namespace ON table_namespace.oid = table_relation.relnamespace "
                            + "JOIN pg_index index_metadata ON index_metadata.indrelid = table_relation.oid "
                            + "JOIN pg_class index_relation ON index_relation.oid = index_metadata.indexrelid "
                            + "WHERE table_namespace.nspname = 'diagrammer' AND table_relation.relname = ? "
                            + "AND NOT EXISTS (SELECT 1 FROM pg_constraint constraint_metadata "
                            + "WHERE constraint_metadata.conrelid = table_relation.oid "
                            + "AND constraint_metadata.conname = index_relation.relname "
                            + "AND constraint_metadata.contype IN ('p', 'u'))",
                    String.class, entry.getKey()));

            assertThat(indexes).as("support indexes of diagrammer.%s", entry.getKey())
                    .containsExactlyInAnyOrderElementsOf(entry.getValue());
        }
    }

    /** The states the partial unique indexes hold against, read out of PostgreSQL itself. */
    private static final Pattern QUOTED_STATE = Pattern.compile("'([A-Z_]+)'");

    /**
     * {@link DeploymentState#isActive()} and the three partial unique indexes must describe the same states.
     *
     * <p>One lives in Java and the other in SQL. A state added to only one of them would quietly stop protecting
     * the destination and the host port pair, which is exactly the invariant this table exists for, so the drift
     * fails here instead of at a deployment.
     */
    @Test
    void activeDeploymentStatesAgreeBetweenJavaAndThePartialIndexes() {
        List<String> javaStates = Arrays.stream(DeploymentState.values())
                .filter(DeploymentState::isActive)
                .map(Enum::name)
                .toList();
        assertThat(javaStates).isNotEmpty();

        List<String> definitions = jdbcTemplate.queryForList(
                "SELECT pg_get_indexdef(index_relation.oid) FROM pg_class index_relation "
                        + "JOIN pg_namespace index_namespace ON index_namespace.oid = index_relation.relnamespace "
                        + "WHERE index_namespace.nspname = 'diagrammer' "
                        + "AND index_relation.relname LIKE 'uq_deployment_active%' "
                        + "ORDER BY index_relation.relname",
                String.class);
        assertThat(definitions).as("the three partial unique indexes of diagrammer.deployment").hasSize(3);

        for (String definition : definitions) {
            List<String> sqlStates = new ArrayList<>();
            Matcher matcher = QUOTED_STATE.matcher(definition);
            while (matcher.find()) {
                sqlStates.add(matcher.group(1));
            }
            assertThat(sqlStates).as("active states in: %s", definition)
                    .containsExactlyInAnyOrderElementsOf(javaStates);
        }
    }

    /** Fragments that would mean a deployment row can hold a credential or a location on this machine. */
    private static final Pattern FORBIDDEN_DEPLOYMENT_COLUMN = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|credential|private[_-]?key|api[_-]?key|vault|connection|"
                    + "uri|url|path|directory|dir|volume|mount)");

    /**
     * A deployment row must never be able to hold a credential or a path on this machine.
     *
     * <p>The operator is the one that keeps the working directory and the {@code .env}, and it reports back only
     * a Compose project name and, on failure, a sanitized reason. This guard fails if a later change gives a
     * deployment column a name that could carry either one.
     */
    @Test
    void deploymentHasNoCredentialOrHostPathColumn() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'diagrammer' AND table_name = 'deployment' ORDER BY column_name",
                String.class);
        assertThat(columns).as("the columns of diagrammer.deployment").isNotEmpty();

        for (String column : columns) {
            assertThat(FORBIDDEN_DEPLOYMENT_COLUMN.matcher(column).find())
                    .as("column diagrammer.deployment.%s must not name a credential or a host path", column)
                    .isFalse();
        }
    }

    @Test
    void citextAndTimestamptzKeepTheirPhysicalType() {
        assertColumnType("user_account", "email", "citext");
        assertColumnType("user_account", "username", "citext");

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
