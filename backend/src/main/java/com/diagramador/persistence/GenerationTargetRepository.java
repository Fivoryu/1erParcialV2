package com.diagramador.persistence;

import com.diagramador.domain.GenerationTarget;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GenerationTargetRepository extends JpaRepository<GenerationTarget, UUID> {

    Optional<GenerationTarget> findByModelIdAndTargetKey(UUID modelId, String targetKey);

    List<GenerationTarget> findByModelIdOrderByTargetKeyAsc(UUID modelId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select target from GenerationTarget target where target.id = :targetId")
    Optional<GenerationTarget> findByIdForUpdate(@Param("targetId") UUID targetId);
}
