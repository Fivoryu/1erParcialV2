package com.diagramador.persistence;

import com.diagramador.domain.Deployment;
import com.diagramador.domain.DeploymentState;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

    List<Deployment> findByRunIdOrderByRequestedAtDesc(UUID runId);

    /** Every deployment of the model that owns this project, newest first. */
    @Query("select d from Deployment d join d.target t where t.model.id = :modelId "
            + "order by d.requestedAt desc")
    List<Deployment> findByModelIdOrderByRequestedAtDesc(@Param("modelId") UUID modelId);

    /** One destination holds at most one active deployment, which the partial unique index also enforces. */
    Optional<Deployment> findByTargetIdAndStateIn(UUID targetId, List<DeploymentState> states);

    /**
     * The host ports every active deployment is holding.
     *
     * <p>Ports live on the machine, not in the model, so the next assignment has to look at every active
     * deployment and not only at the ones of this destination's model.
     */
    @Query("select d.httpHostPort from Deployment d where d.state in :states "
            + "union select d.postgresHostPort from Deployment d where d.state in :states")
    List<Integer> findActiveHostPorts(@Param("states") List<DeploymentState> states);

    /**
     * Takes the order on behalf of one operator, but only if nobody took it first.
     *
     * <p>The state lives in the {@code WHERE} clause on purpose: two operators racing on the same order both
     * issue this statement, and the database lets exactly one of them change a row. Reading the row and then
     * writing it would let both believe they won.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Deployment d set d.state = :taken, d.operatorId = :operatorId, d.startedAt = :now, "
            + "d.updatedAt = :now where d.id = :id and d.state = :pending")
    int claimIfPending(@Param("id") UUID id, @Param("pending") DeploymentState pending,
            @Param("taken") DeploymentState taken, @Param("operatorId") String operatorId,
            @Param("now") OffsetDateTime now);
}
