package com.diagramador.persistence;

import com.diagramador.domain.UmlNoteLink;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UmlNoteLinkRepository extends JpaRepository<UmlNoteLink, UmlNoteLink.Key> {

    List<UmlNoteLink> findByKeyNoteId(UUID noteId);

    List<UmlNoteLink> findByKeyNoteIdIn(Collection<UUID> noteIds);

    List<UmlNoteLink> findByKeyTargetIdIn(Collection<UUID> targetIds);
}
