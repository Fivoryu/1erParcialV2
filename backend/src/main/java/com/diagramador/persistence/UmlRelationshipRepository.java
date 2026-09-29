package com.diagramador.persistence;

import com.diagramador.domain.UmlRelationship;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UmlRelationshipRepository extends JpaRepository<UmlRelationship, UUID> {

    List<UmlRelationship> findByModelId(UUID modelId);
}
