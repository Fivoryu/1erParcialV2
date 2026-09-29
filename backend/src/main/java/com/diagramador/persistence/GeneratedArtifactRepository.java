package com.diagramador.persistence;

import com.diagramador.domain.GeneratedArtifact;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeneratedArtifactRepository extends JpaRepository<GeneratedArtifact, UUID> {
}
