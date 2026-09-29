package com.diagramador.persistence;

import com.diagramador.domain.GenerationTarget;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationTargetRepository extends JpaRepository<GenerationTarget, UUID> {
}
