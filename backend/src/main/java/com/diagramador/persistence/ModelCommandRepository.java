package com.diagramador.persistence;

import com.diagramador.domain.ModelCommand;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelCommandRepository extends JpaRepository<ModelCommand, UUID> {

    Optional<ModelCommand> findByModelIdAndIdempotencyKey(UUID modelId, String idempotencyKey);

    long countByModelId(UUID modelId);
}
