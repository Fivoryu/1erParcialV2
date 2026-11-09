package com.diagramador.vcs;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/** Upgrade an isolated synthetic database containing a request and approval from V31. */
class VcsReviewerMigrationUpgradeTest {
    @Test
    void existingRequestsAndApprovalsSurviveTheAdditiveMigration() throws Exception {
        try (PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine")) {
            db.start();
            Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                    .schemas("diagrammer").target("31").load().migrate();
            try (Connection connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                    Statement sql = connection.createStatement()) {
                sql.execute("INSERT INTO diagrammer.project (project_id, owner_user_id, name) VALUES "
                        + "('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'synthetic')");
                sql.execute("INSERT INTO diagrammer.uml_model (model_id, project_id, name) VALUES "
                        + "('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'synthetic')");
                sql.execute("INSERT INTO diagrammer.vcs_snapshot (snapshot_id, model_id, state_hash, payload) VALUES "
                        + "('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', repeat('a',64), '{}')");
                sql.execute("INSERT INTO diagrammer.vcs_commit (commit_id, model_id, snapshot_id, branch_name, message) VALUES "
                        + "('40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'main', 'synthetic')");
                sql.execute("INSERT INTO diagrammer.vcs_branch (branch_id, model_id, name, head_commit_id) VALUES "
                        + "('50000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'main', '40000000-0000-0000-0000-000000000001'), "
                        + "('50000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', 'feature', '40000000-0000-0000-0000-000000000001')");
                sql.execute("INSERT INTO diagrammer.vcs_merge_request (merge_request_id, model_id, source_branch_id, target_branch_id, source_commit_id, target_commit_id, title) VALUES "
                        + "('60000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', 'synthetic review')");
                sql.execute("INSERT INTO diagrammer.vcs_merge_request_approval (merge_request_id, user_id) VALUES "
                        + "('60000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001')");
                Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                        .schemas("diagrammer").target("32").load().migrate();
                sql.execute("INSERT INTO diagrammer.vcs_merge_request_reviewer (merge_request_id, user_id) VALUES "
                        + "('60000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001')");
                Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                        .schemas("diagrammer").target("33").load().migrate();
                try (ResultSet rows = sql.executeQuery("SELECT r.title, r.state::text, "
                        + "(SELECT count(*) FROM diagrammer.vcs_merge_request_approval), "
                        + "(SELECT count(*) FROM diagrammer.vcs_merge_request_reviewer), "
                        + "(SELECT count(*) FROM diagrammer.vcs_merge_request_review) "
                        + "FROM diagrammer.vcs_merge_request r WHERE r.merge_request_id = '60000000-0000-0000-0000-000000000001'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("synthetic review");
                    assertThat(rows.getString(2)).isEqualTo("OPEN");
                    assertThat(rows.getLong(3)).isEqualTo(1);
                    assertThat(rows.getLong(4)).isEqualTo(1);
                    assertThat(rows.getLong(5)).isZero();
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }
}
