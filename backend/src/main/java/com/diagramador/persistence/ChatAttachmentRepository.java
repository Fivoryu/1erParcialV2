package com.diagramador.persistence;

import com.diagramador.domain.ChatAttachment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, UUID> {

    List<ChatAttachment> findByMessage_IdOrderByCreatedAtAscIdAsc(UUID messageId);

    List<ChatAttachment> findBySession_IdAndMessageIsNullOrderByCreatedAtAscIdAsc(UUID sessionId);
}
