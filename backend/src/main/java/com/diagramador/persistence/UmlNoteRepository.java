package com.diagramador.persistence;

import com.diagramador.domain.UmlNote;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UmlNoteRepository extends JpaRepository<UmlNote, UUID> {

    List<UmlNote> findByModelId(UUID modelId);
}
