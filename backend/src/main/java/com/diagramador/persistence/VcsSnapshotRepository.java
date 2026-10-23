package com.diagramador.persistence;

import com.diagramador.domain.VcsSnapshot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VcsSnapshotRepository extends JpaRepository<VcsSnapshot, UUID> {

    Optional<VcsSnapshot> findByModel_IdAndStateHash(UUID modelId, String stateHash);

    long countByModel_Id(UUID modelId);
}
