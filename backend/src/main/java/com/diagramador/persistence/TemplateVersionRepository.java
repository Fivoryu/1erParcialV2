package com.diagramador.persistence;

import com.diagramador.domain.TemplateVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateVersionRepository extends JpaRepository<TemplateVersion, UUID> {

    Optional<TemplateVersion> findFirstByProfileIdOrderByRevisionDesc(UUID profileId);

    Optional<TemplateVersion> findByProfileIdAndRevision(UUID profileId, int revision);
}
