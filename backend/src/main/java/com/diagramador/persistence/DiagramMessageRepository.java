package com.diagramador.persistence;

import com.diagramador.domain.DiagramMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiagramMessageRepository extends JpaRepository<DiagramMessage, UUID> {

    List<DiagramMessage> findByModel_IdOrderByCreatedAtAscIdAsc(UUID modelId);

    List<DiagramMessage> findBySession_IdOrderByCreatedAtAscIdAsc(UUID sessionId);

    /**
     * PD-06 (L34): deleting a session's history is a physical delete of only that session's
     * messages; the session row survives empty and reusable. A single bulk DELETE (not the
     * derived select-then-remove loop) is atomic, so an AI answer the worker inserts while the
     * OWNER purges either commits before the statement (and is deleted with the rest) or fails
     * on the question's FK — it can never survive as an unlinked row (verifier finding, L40).
     * Returns the exact deleted-row count.
     */
    @Modifying
    @Query("DELETE FROM DiagramMessage m WHERE m.session.id = :sessionId")
    int deleteBySession_Id(@Param("sessionId") UUID sessionId);
}
