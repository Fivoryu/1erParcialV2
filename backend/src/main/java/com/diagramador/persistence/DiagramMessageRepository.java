package com.diagramador.persistence;

import com.diagramador.domain.DiagramMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface DiagramMessageRepository extends JpaRepository<DiagramMessage, UUID> {

    List<DiagramMessage> findByModel_IdOrderByCreatedAtAscIdAsc(UUID modelId);

    List<DiagramMessage> findBySession_IdOrderByCreatedAtAscIdAsc(UUID sessionId);

    /**
     * PD-06 (L34): deleting a session's history is a physical delete of only that session's
     * messages; the session row survives empty and reusable. Returns the deleted row count.
     */
    @Transactional
    long deleteBySession_Id(UUID sessionId);
}
