package com.diagramador.persistence;

import com.diagramador.domain.ModelCommand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelCommandRepository extends JpaRepository<ModelCommand, UUID> {

    Optional<ModelCommand> findByModelIdAndIdempotencyKey(UUID modelId, String idempotencyKey);

    long countByModelId(UUID modelId);

    // T4/L41: the accepted-change log read API. Accepted rows are those with a result revision.
    List<ModelCommand> findByModelIdAndResultRevisionNotNullOrderByResultRevisionDesc(UUID modelId,
            Pageable pageable);

    List<ModelCommand> findByModelIdAndResultRevisionNotNullAndResultRevisionGreaterThanOrderByResultRevisionDesc(
            UUID modelId, long sinceRevision, Pageable pageable);

    long countByModelIdAndKnowledgePendingTrue(UUID modelId);
}
