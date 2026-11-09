package com.diagramador.persistence;

import com.diagramador.domain.VcsMergeRequestReview;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VcsMergeRequestReviewRepository extends JpaRepository<VcsMergeRequestReview, UUID> {
    List<VcsMergeRequestReview> findByMergeRequest_IdOrderByCreatedAtAscIdAsc(UUID mergeRequestId);
}
