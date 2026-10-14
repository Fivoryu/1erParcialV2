package com.diagramador.mobile.agent;

import com.diagramador.api.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Short PostgreSQL transactions serialize receipt identity, never upstream I/O. */
@Repository
public class MobileAgentOperationStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public MobileAgentOperationStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Stored(UUID operationId, UUID userId, UUID projectId, UUID deploymentId,
            String contractHash, String canonical, String state, Integer status, String result, String error) { }
    public record Claim(Stored stored, boolean dispatch) { }

    public Claim claim(UUID user, UUID project, UUID deployment, MobileAgentOperationResources.Request request, String canonical) {
        return transactions.execute(transaction -> {
            boolean delete = "DELETE".equals(request.method());
            String initial = delete ? "awaiting_confirmation" : "dispatching";
            int inserted = jdbc.update("""
                    INSERT INTO diagrammer.agent_operation
                      (operation_id,user_id,project_id,deployment_id,contract_hash,canonical_payload,state)
                    VALUES (?,?,?,?,?,?,?) ON CONFLICT (operation_id) DO NOTHING
                    """, request.operationId(), user, project, deployment, request.contractHash(), canonical, initial);
            Stored stored = find(request.operationId(), true);
            requireScope(stored, user, project, deployment);
            if (!canonical.equals(stored.canonical())) throw failure("OPERATION_CONFLICT", HttpStatus.CONFLICT);
            if (!delete) return new Claim(stored, inserted == 1);
            String confirmation = request.confirmationToken();
            if (confirmation == null) return new Claim(stored, false);
            String expected = MobileAgentOperationResources.token(user, project, deployment, request.operationId(), canonical);
            // A first request cannot skip obtaining the persisted preview, even with a computed token.
            if (inserted == 1 || !expected.equals(confirmation)) throw failure("CONFIRMATION_REQUIRED", HttpStatus.CONFLICT);
            if (!"awaiting_confirmation".equals(stored.state())) return new Claim(stored, false);
            jdbc.update("UPDATE diagrammer.agent_operation SET state='dispatching', updated_at=CURRENT_TIMESTAMP WHERE operation_id=?",
                    request.operationId());
            return new Claim(find(request.operationId(), true), true);
        });
    }

    public Stored lookup(UUID user, UUID project, UUID deployment, UUID operation) {
        Stored stored = find(operation, false);
        requireScope(stored, user, project, deployment);
        return stored;
    }

    public Stored finish(UUID operation, Integer status, String result, String error) {
        return transactions.execute(transaction -> {
            jdbc.update("""
                    UPDATE diagrammer.agent_operation SET state=?,response_status=?,response_body=?,error_code=?,updated_at=CURRENT_TIMESTAMP
                    WHERE operation_id=? AND state='dispatching'
                    """, error == null ? "completed" : "unknown", status, result, error, operation);
            return find(operation, false);
        });
    }

    private Stored find(UUID operation, boolean lock) {
        List<Stored> rows = jdbc.query("SELECT * FROM diagrammer.agent_operation WHERE operation_id=?" + (lock ? " FOR UPDATE" : ""),
                (result, row) -> new Stored(result.getObject("operation_id", UUID.class), result.getObject("user_id", UUID.class),
                        result.getObject("project_id", UUID.class), result.getObject("deployment_id", UUID.class),
                        result.getString("contract_hash"), result.getString("canonical_payload"), result.getString("state"),
                        result.getObject("response_status", Integer.class), result.getString("response_body"), result.getString("error_code")), operation);
        if (rows.isEmpty()) throw failure("OPERATION_NOT_FOUND", HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }

    private static void requireScope(Stored stored, UUID user, UUID project, UUID deployment) {
        if (!user.equals(stored.userId()) || !project.equals(stored.projectId()) || !deployment.equals(stored.deploymentId())) {
            throw failure("OPERATION_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
    }

    static ApiException failure(String code, HttpStatus status) {
        return new ApiException(status, code, "The agent operation request was rejected.", List.of(), null);
    }
}
