package com.diagramador.persistence;

import com.diagramador.domain.ChatProposal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatProposalRepository extends JpaRepository<ChatProposal, UUID> {

    List<ChatProposal> findBySession_IdOrderByCreatedAtAscIdAsc(UUID sessionId);

    /**
     * Moves a proposal from {@code from} to {@code to} only if it is still in {@code from}; the
     * returned count (0 or 1) is how concurrent deciders learn that someone else won.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ChatProposal p set p.status = :to where p.id = :id and p.session.id = :sessionId "
            + "and p.status = :from")
    int transition(@Param("id") UUID id, @Param("sessionId") UUID sessionId, @Param("from") String from,
            @Param("to") String to);
}
