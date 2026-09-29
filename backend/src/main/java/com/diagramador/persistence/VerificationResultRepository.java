package com.diagramador.persistence;

import com.diagramador.domain.VerificationResult;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationResultRepository extends JpaRepository<VerificationResult, UUID> {
}
