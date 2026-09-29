package com.diagramador.persistence;

import com.diagramador.domain.Tombstone;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TombstoneRepository extends JpaRepository<Tombstone, UUID> {

    List<Tombstone> findByModelId(UUID modelId);

    List<Tombstone> findByCascadeCascadeId(UUID cascadeId);
}
