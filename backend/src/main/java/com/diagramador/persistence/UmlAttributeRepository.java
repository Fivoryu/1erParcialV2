package com.diagramador.persistence;

import com.diagramador.domain.UmlAttribute;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UmlAttributeRepository extends JpaRepository<UmlAttribute, UUID> {

    List<UmlAttribute> findByClassifierId(UUID classifierId);
}
