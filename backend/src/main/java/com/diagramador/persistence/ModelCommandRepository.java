package com.diagramador.persistence;

import com.diagramador.domain.ModelCommand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModelCommandRepository extends JpaRepository<ModelCommand, UUID> {

    Optional<ModelCommand> findByModelIdAndIdempotencyKey(UUID modelId, String idempotencyKey);

    long countByModelId(UUID modelId);

    // T4/L41: the accepted-change log read API. Accepted rows are those with a result revision.
    List<ModelCommand> findByModelIdAndResultRevisionNotNullOrderByResultRevisionDesc(UUID modelId,
            Pageable pageable);

    List<ModelCommand> findByModelIdAndResultRevisionNotNullAndResultRevisionGreaterThanOrderByResultRevisionDesc(
            UUID modelId, long sinceRevision, Pageable pageable);

    long countByModelIdAndKnowledgePendingTrue(UUID modelId);

    // T5/L43: pending accepted changes feeding memory extraction, oldest first, capped by the caller.
    List<ModelCommand> findByModelIdAndKnowledgePendingTrueOrderByCreatedAtAscCommandIdAsc(UUID modelId,
            Pageable pageable);

    @Modifying
    @Query("UPDATE ModelCommand c SET c.knowledgePending = false "
            + "WHERE c.model.id = :modelId AND c.knowledgePending = true")
    int markKnowledgeConsumed(@Param("modelId") UUID modelId);
}
