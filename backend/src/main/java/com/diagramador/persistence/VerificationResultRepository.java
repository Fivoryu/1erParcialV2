package com.diagramador.persistence;

import com.diagramador.domain.VerificationResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationResultRepository extends JpaRepository<VerificationResult, UUID> {

    Optional<VerificationResult> findByRunIdAndCheckName(UUID runId, String checkName);

    List<VerificationResult> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
