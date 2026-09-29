package com.diagramador.persistence;

import com.diagramador.domain.UmlModel;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UmlModelRepository extends JpaRepository<UmlModel, UUID> {

    Optional<UmlModel> findByProjectId(UUID projectId);
}
