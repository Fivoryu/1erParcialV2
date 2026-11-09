package com.diagramador.persistence;

import com.diagramador.domain.VcsMergeRequest;
import com.diagramador.domain.VcsMergeState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VcsMergeRequestRepository extends JpaRepository<VcsMergeRequest, UUID> {

    Optional<VcsMergeRequest> findByModel_IdAndId(UUID modelId, UUID mergeRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from VcsMergeRequest r where r.model.id = :modelId and r.id = :mergeRequestId")
    Optional<VcsMergeRequest> findForUpdate(@Param("modelId") UUID modelId,
            @Param("mergeRequestId") UUID mergeRequestId);

    List<VcsMergeRequest> findByModel_IdOrderByCreatedAtDescIdDesc(UUID modelId);

    List<VcsMergeRequest> findByModel_IdAndStateOrderByCreatedAtDescIdDesc(UUID modelId, VcsMergeState state);

    boolean existsBySourceBranch_IdAndTargetBranch_IdAndState(UUID sourceBranchId, UUID targetBranchId,
            VcsMergeState state);
}
