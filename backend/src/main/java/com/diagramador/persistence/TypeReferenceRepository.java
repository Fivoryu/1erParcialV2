package com.diagramador.persistence;

import com.diagramador.domain.TypeReference;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TypeReferenceRepository extends JpaRepository<TypeReference, UUID> {

    List<TypeReference> findByModelId(UUID modelId);

    java.util.Optional<TypeReference> findFirstByModelIdAndPrimitiveName(UUID modelId, String primitiveName);

    java.util.Optional<TypeReference> findFirstByModelIdAndClassifierId(UUID modelId, UUID classifierId);
}
