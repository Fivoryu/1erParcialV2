package com.diagramador.persistence;

import com.diagramador.domain.ConnectorPath;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorPathRepository extends JpaRepository<ConnectorPath, UUID> {

    List<ConnectorPath> findByViewId(UUID viewId);

    List<ConnectorPath> findByRelationshipId(UUID relationshipId);
}
