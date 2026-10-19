package com.diagramador.mobile.chat;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
public interface AgentConversationRepository extends JpaRepository<AgentConversation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AgentConversation c where c.id = :id")
    Optional<AgentConversation> lockById(UUID id);
    @Query("select c from AgentConversation c where c.userId=:userId and c.projectId=:projectId and c.deploymentId=:deploymentId and (:cursor is null or c.id > :cursor) order by c.id")
    List<AgentConversation> history(UUID userId, UUID projectId, UUID deploymentId, UUID cursor, Pageable page);
}
