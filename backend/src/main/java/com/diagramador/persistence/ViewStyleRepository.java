package com.diagramador.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Rows of diagrammer.view_style: one per (view, target) appearance entry. */
public interface ViewStyleRepository extends JpaRepository<ViewStyle, UUID> {

    List<ViewStyle> findByViewId(UUID viewId);

    Optional<ViewStyle> findByViewIdAndTargetId(UUID viewId, UUID targetId);
}
