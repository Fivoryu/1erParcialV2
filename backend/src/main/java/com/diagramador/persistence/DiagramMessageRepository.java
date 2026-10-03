package com.diagramador.persistence;

import com.diagramador.domain.DiagramMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagramMessageRepository extends JpaRepository<DiagramMessage, UUID> {

    List<DiagramMessage> findByModel_IdOrderByCreatedAtAscIdAsc(UUID modelId);
}
