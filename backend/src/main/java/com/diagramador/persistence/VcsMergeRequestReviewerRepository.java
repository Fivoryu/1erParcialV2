package com.diagramador.persistence;

import com.diagramador.domain.VcsMergeRequestApproval;
import com.diagramador.domain.VcsMergeRequestReviewer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VcsMergeRequestReviewerRepository
        extends JpaRepository<VcsMergeRequestReviewer, VcsMergeRequestApproval.Key> {
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO diagrammer.vcs_merge_request_reviewer (merge_request_id, user_id) "
            + "VALUES (:requestId, :userId) ON CONFLICT (merge_request_id, user_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("requestId") UUID requestId, @Param("userId") UUID userId);

    List<VcsMergeRequestReviewer> findByMergeRequest_IdOrderByUser_IdAsc(UUID requestId);
}
