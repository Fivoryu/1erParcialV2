package com.diagramador.persistence;

import com.diagramador.domain.UmlConstraint;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UmlConstraintRepository extends JpaRepository<UmlConstraint, UUID> {

    List<UmlConstraint> findByModelId(UUID modelId);
}
