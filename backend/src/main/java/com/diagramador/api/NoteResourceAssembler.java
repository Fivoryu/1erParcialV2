package com.diagramador.api;

import com.diagramador.api.ModelResource.NoteLinkResource;
import com.diagramador.api.ModelResource.NoteResource;
import com.diagramador.domain.UmlNote;
import com.diagramador.domain.UmlNoteLink;
import com.diagramador.persistence.UmlNoteLinkRepository;
import com.diagramador.persistence.UmlNoteRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Reads the notes of a model, with their annotated elements, in the order every consumer shares. */
@Component
public class NoteResourceAssembler {

    private static final Comparator<UmlNoteLink> LINK_ORDER = Comparator
            .comparing((UmlNoteLink link) -> link.getTargetType().name())
            .thenComparing(link -> link.getTargetId().toString());

    private final UmlNoteRepository noteRepository;
    private final UmlNoteLinkRepository linkRepository;

    public NoteResourceAssembler(UmlNoteRepository noteRepository, UmlNoteLinkRepository linkRepository) {
        this.noteRepository = noteRepository;
        this.linkRepository = linkRepository;
    }

    /** Notes of the model ordered by id; each lists its annotated elements ordered by type then id. */
    public List<NoteResource> forModel(UUID modelId) {
        List<UmlNote> notes = noteRepository.findByModelId(modelId).stream()
                .sorted(Comparator.comparing(note -> note.getId().toString()))
                .toList();
        if (notes.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<UmlNoteLink>> links = linkRepository
                .findByKeyNoteIdIn(notes.stream().map(UmlNote::getId).toList()).stream()
                .collect(Collectors.groupingBy(UmlNoteLink::getNoteId));
        return notes.stream()
                .map(note -> new NoteResource(note.getId(), note.getBody(),
                        links.getOrDefault(note.getId(), List.of()).stream()
                                .sorted(LINK_ORDER)
                                .map(link -> new NoteLinkResource(link.getTargetType(), link.getTargetId()))
                                .toList()))
                .toList();
    }
}
