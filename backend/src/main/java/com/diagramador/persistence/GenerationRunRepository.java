package com.diagramador.persistence;

import com.diagramador.domain.GenerationRun;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationRunRepository extends JpaRepository<GenerationRun, UUID> {

    Optional<GenerationRun> findByTargetIdAndTargetConfigRevisionAndVersionIdAndProfileIdAndTemplateId(
            UUID targetId, int targetConfigRevision, UUID versionId, UUID profileId, UUID templateId);

    long countByTargetId(UUID targetId);
}
