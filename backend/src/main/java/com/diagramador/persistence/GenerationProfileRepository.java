package com.diagramador.persistence;

import com.diagramador.domain.GenerationProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationProfileRepository extends JpaRepository<GenerationProfile, UUID> {

    Optional<GenerationProfile> findFirstByProjectIdAndProfileKeyOrderByRevisionDesc(UUID projectId,
            String profileKey);

    Optional<GenerationProfile> findByProjectIdAndProfileKeyAndRevision(UUID projectId, String profileKey,
            int revision);
}
