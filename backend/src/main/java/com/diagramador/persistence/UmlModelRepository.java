package com.diagramador.persistence;

import com.diagramador.domain.UmlModel;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UmlModelRepository extends JpaRepository<UmlModel, UUID> {

    Optional<UmlModel> findByProjectId(UUID projectId);

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE diagrammer.uml_model SET current_revision = :nextRevision "
            + "WHERE model_id = :modelId AND current_revision = :expectedRevision", nativeQuery = true)
    int bumpRevisionIfCurrent(@Param("modelId") UUID modelId, @Param("expectedRevision") long expectedRevision,
            @Param("nextRevision") long nextRevision);
}
