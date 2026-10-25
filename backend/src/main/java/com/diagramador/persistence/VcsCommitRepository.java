package com.diagramador.persistence;

import com.diagramador.domain.VcsCommit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VcsCommitRepository extends JpaRepository<VcsCommit, UUID> {

    Optional<VcsCommit> findByModel_IdAndId(UUID modelId, UUID commitId);

    List<VcsCommit> findByModel_IdOrderByCreatedAtDescIdDesc(UUID modelId, Pageable pageable);

    List<VcsCommit> findByModel_IdAndBranchNameOrderByCreatedAtDescIdDesc(UUID modelId, String branchName,
            Pageable pageable);

    long countByModel_Id(UUID modelId);
}
