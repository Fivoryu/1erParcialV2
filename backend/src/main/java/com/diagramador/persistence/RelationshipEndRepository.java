package com.diagramador.persistence;

import com.diagramador.domain.RelationshipEnd;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelationshipEndRepository extends JpaRepository<RelationshipEnd, UUID> {

    List<RelationshipEnd> findByRelationshipId(UUID relationshipId);

    List<RelationshipEnd> findByClassifierId(UUID classifierId);
}
