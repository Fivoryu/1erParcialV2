package com.diagramador.undo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UndoLogRepository extends JpaRepository<UndoLogEntry, UUID> {

    /** LIFO: the user's latest entry that has not been undone yet. */
    Optional<UndoLogEntry> findFirstByModel_IdAndUser_IdAndUndoneAtIsNullOrderByCreatedAtDescIdDesc(UUID modelId,
            UUID userId);

    /** LIFO: the user's latest undone entry that has not been redone yet. */
    Optional<UndoLogEntry> findFirstByModel_IdAndUser_IdAndUndoneAtIsNotNullAndRedoneAtIsNullOrderByUndoneAtDescIdDesc(
            UUID modelId, UUID userId);

    List<UndoLogEntry> findByModel_IdOrderByCreatedAtAscIdAsc(UUID modelId);
}
