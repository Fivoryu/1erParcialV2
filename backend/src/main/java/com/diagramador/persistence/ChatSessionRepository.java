package com.diagramador.persistence;

import com.diagramador.domain.ChatSession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    List<ChatSession> findByView_IdOrderByCreatedAtAscIdAsc(UUID viewId);

    Optional<ChatSession> findByViewModelIdAndIsDefaultTrue(UUID modelId);
}
