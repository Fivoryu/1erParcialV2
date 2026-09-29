package com.diagramador.persistence;

import com.diagramador.domain.GenerationProfile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationProfileRepository extends JpaRepository<GenerationProfile, UUID> {
}
