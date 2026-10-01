package com.diagramador.persistence;

import com.diagramador.domain.Deployment;
import com.diagramador.domain.DeploymentState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

    List<Deployment> findByRunIdOrderByRequestedAtDesc(UUID runId);

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
     * The next free host index for the {@code 1900N}/{@code 2543N} convention, honouring what other models
     * already hold.
     */
    @Query("select coalesce(max(d.httpHostPort), 19000) from Deployment d")
    Integer findHighestHttpHostPort();
}
