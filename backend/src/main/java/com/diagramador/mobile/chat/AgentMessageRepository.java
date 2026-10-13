package com.diagramador.mobile.chat;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
public interface AgentMessageRepository extends JpaRepository<AgentMessage, UUID> {
    List<AgentMessage> findByConversationIdAndSequenceGreaterThanOrderBySequenceAsc(UUID conversationId, long sequence, Pageable page);
}
