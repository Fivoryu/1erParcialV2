package com.diagramador.persistence;

import com.diagramador.domain.Invitation;
import com.diagramador.domain.InvitationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    List<Invitation> findByProject_IdOrderByCreatedAtAscIdAsc(UUID projectId);

    List<Invitation> findByInvitee_IdOrderByCreatedAtAscIdAsc(UUID inviteeId);

    Optional<Invitation> findByProject_IdAndId(UUID projectId, UUID invitationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.project.id = :projectId and i.id = :invitationId")
    Optional<Invitation> findForTransition(@Param("projectId") UUID projectId,
            @Param("invitationId") UUID invitationId);

    boolean existsByProject_IdAndInvitee_IdAndStatus(UUID projectId, UUID inviteeId, InvitationStatus status);
}
