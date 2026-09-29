package com.diagramador.persistence;

import com.diagramador.domain.DiagramView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagramViewRepository extends JpaRepository<DiagramView, UUID> {

    List<DiagramView> findByModelId(UUID modelId);
}
