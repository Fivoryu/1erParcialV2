package com.diagramador.persistence;

import com.diagramador.domain.Invitation;
import com.diagramador.domain.InvitationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    List<Invitation> findByProject_IdOrderByCreatedAtAscIdAsc(UUID projectId);

    List<Invitation> findByInvitee_IdOrderByCreatedAtAscIdAsc(UUID inviteeId);

    Optional<Invitation> findByProject_IdAndId(UUID projectId, UUID invitationId);

    boolean existsByProject_IdAndInvitee_IdAndStatus(UUID projectId, UUID inviteeId, InvitationStatus status);
}
