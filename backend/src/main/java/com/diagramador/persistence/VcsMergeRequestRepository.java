package com.diagramador.persistence;

import com.diagramador.domain.VcsMergeRequest;
import com.diagramador.domain.VcsMergeState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VcsMergeRequestRepository extends JpaRepository<VcsMergeRequest, UUID> {

    Optional<VcsMergeRequest> findByModel_IdAndId(UUID modelId, UUID mergeRequestId);

    List<VcsMergeRequest> findByModel_IdOrderByCreatedAtDescIdDesc(UUID modelId);

    List<VcsMergeRequest> findByModel_IdAndStateOrderByCreatedAtDescIdDesc(UUID modelId, VcsMergeState state);

    boolean existsBySourceBranch_IdAndTargetBranch_IdAndState(UUID sourceBranchId, UUID targetBranchId,
            VcsMergeState state);
}
