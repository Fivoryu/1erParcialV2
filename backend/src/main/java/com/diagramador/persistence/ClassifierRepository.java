package com.diagramador.persistence;

import com.diagramador.domain.Classifier;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassifierRepository extends JpaRepository<Classifier, UUID> {

    List<Classifier> findByModelId(UUID modelId);

    List<Classifier> findByNamespaceId(UUID namespaceId);
}
