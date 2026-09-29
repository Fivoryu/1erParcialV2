package com.diagramador.persistence;

import com.diagramador.domain.ElementGeometry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ElementGeometryRepository extends JpaRepository<ElementGeometry, UUID> {

    List<ElementGeometry> findByViewId(UUID viewId);
}
