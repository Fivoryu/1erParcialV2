package com.diagramador.persistence;

import com.diagramador.domain.ChatSessionCompaction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionCompactionRepository extends JpaRepository<ChatSessionCompaction, UUID> {

    Optional<ChatSessionCompaction> findBySession_Id(UUID sessionId);

    void deleteBySession_Id(UUID sessionId);
}
