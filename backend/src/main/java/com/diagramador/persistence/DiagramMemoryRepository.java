package com.diagramador.persistence;

import com.diagramador.domain.DiagramMemory;
import com.diagramador.domain.MemoryStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiagramMemoryRepository extends JpaRepository<DiagramMemory, UUID> {

    List<DiagramMemory> findByView_IdOrderByCreatedAtAscIdAsc(UUID viewId);

    List<DiagramMemory> findByView_IdAndStatusOrderByCreatedAtAscIdAsc(UUID viewId, MemoryStatus status);

    /**
     * PD-06 analog for memory (T5/L43): a single atomic bulk DELETE of the whole diagram's
     * memories, returning the exact count (same reasoning as the history purge, L40).
     */
    @Modifying
    @Query("DELETE FROM DiagramMemory m WHERE m.view.id = :viewId")
    int deleteByView_Id(@Param("viewId") UUID viewId);
}
