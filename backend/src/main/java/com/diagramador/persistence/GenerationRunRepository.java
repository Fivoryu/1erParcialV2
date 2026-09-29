package com.diagramador.persistence;

import com.diagramador.domain.GenerationRun;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationRunRepository extends JpaRepository<GenerationRun, UUID> {
}
