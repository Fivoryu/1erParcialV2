package com.diagramador.persistence;

import com.diagramador.domain.CascadeGroup;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CascadeGroupRepository extends JpaRepository<CascadeGroup, UUID> {

    List<CascadeGroup> findByModelId(UUID modelId);
}
