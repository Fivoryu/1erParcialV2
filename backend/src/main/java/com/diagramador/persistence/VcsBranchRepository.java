package com.diagramador.persistence;

import com.diagramador.domain.VcsBranch;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VcsBranchRepository extends JpaRepository<VcsBranch, UUID> {

    Optional<VcsBranch> findByModel_IdAndName(UUID modelId, String name);

    List<VcsBranch> findByModel_IdOrderByNameAsc(UUID modelId);

    boolean existsByModel_IdAndName(UUID modelId, String name);

    long countByModel_Id(UUID modelId);
}
