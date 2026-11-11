package com.diagramador.command.handler;

import com.diagramador.command.CommandHandler;
import com.diagramador.domain.ElementGeometry;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.UmlNote;
import com.diagramador.persistence.ElementGeometryRepository;
import com.diagramador.persistence.UmlNoteRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/** Deletes a note, its links to annotated elements (database cascade) and its canvas placements. */
@Component
public class NoteDeleteHandler implements CommandHandler.DeleteAction {

    private final CommandHandlerSupport support;
    private final UmlNoteRepository noteRepository;
    private final ElementGeometryRepository geometryRepository;

    public NoteDeleteHandler(CommandHandlerSupport support, UmlNoteRepository noteRepository,
            ElementGeometryRepository geometryRepository) {
        this.support = support;
        this.noteRepository = noteRepository;
        this.geometryRepository = geometryRepository;
    }

    @Override
    public String type() {
        return "note.delete";
    }

    @Override
    public Outcome apply(Context context) {
        support.noPayload(context);
        UmlNote note = support.note(context);
        List<ElementGeometry> placements = geometryRepository.findByViewModelId(context.model().getId()).stream()
                .filter(geometry -> geometry.getTargetType() == TargetType.NOTE
                        && geometry.getTargetId().equals(note.getId()))
                .toList();
        geometryRepository.deleteAll(placements);
        noteRepository.delete(note);
        return Outcome.deleted(TargetType.NOTE, note.getId());
    }
}
