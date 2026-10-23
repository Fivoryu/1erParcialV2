package com.diagramador.persistence;

import com.diagramador.domain.VcsAction;
import com.diagramador.domain.VcsPermission;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VcsPermissionRepository extends JpaRepository<VcsPermission, UUID> {

    Optional<VcsPermission> findByModel_IdAndUser_IdAndAction(UUID modelId, UUID userId, VcsAction action);

    List<VcsPermission> findByModel_IdAndUser_Id(UUID modelId, UUID userId);

    List<VcsPermission> findByModel_IdOrderByCreatedAtAscIdAsc(UUID modelId);

    boolean existsByModel_IdAndUser_IdAndAction(UUID modelId, UUID userId, VcsAction action);
}
