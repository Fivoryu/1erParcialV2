package com.diagramador.persistence;

import com.diagramador.domain.ModelVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelVersionRepository extends JpaRepository<ModelVersion, UUID> {

    long countByModelId(UUID modelId);

    Optional<ModelVersion> findTopByModelIdOrderByRevisionDesc(UUID modelId);
}
