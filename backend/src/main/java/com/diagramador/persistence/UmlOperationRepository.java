package com.diagramador.persistence;

import com.diagramador.domain.UmlOperation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UmlOperationRepository extends JpaRepository<UmlOperation, UUID> {

    List<UmlOperation> findByClassifierId(UUID classifierId);
}
