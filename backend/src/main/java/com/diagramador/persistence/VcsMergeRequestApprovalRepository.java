package com.diagramador.persistence;

import com.diagramador.domain.VcsMergeRequestApproval;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VcsMergeRequestApprovalRepository
        extends JpaRepository<VcsMergeRequestApproval, VcsMergeRequestApproval.Key> {

    /** Idempotent at the database level: two concurrent first approvals by one user leave a single row. */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO diagrammer.vcs_merge_request_approval (merge_request_id, user_id) "
            + "VALUES (:mergeRequestId, :userId) ON CONFLICT (merge_request_id, user_id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("mergeRequestId") UUID mergeRequestId, @Param("userId") UUID userId);

    List<VcsMergeRequestApproval> findByMergeRequest_IdOrderByApprovedAtAscUser_IdAsc(
            java.util.UUID mergeRequestId);
}
