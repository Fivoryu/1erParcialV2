package com.diagramador.command.handler;

import com.diagramador.command.CommandHandler;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.UmlNote;
import com.diagramador.domain.UmlNoteLink;
import com.diagramador.persistence.UmlNoteLinkRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Removes one annotated element from a note; the element itself is untouched. */
@Component
public class NoteUnlinkHandler implements CommandHandler.AddAction {

    private final CommandHandlerSupport support;
    private final UmlNoteLinkRepository linkRepository;

    public NoteUnlinkHandler(CommandHandlerSupport support, UmlNoteLinkRepository linkRepository) {
        this.support = support;
        this.linkRepository = linkRepository;
    }

    @Override
    public String type() {
        return "note.unlink";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, NoteLinkHandler.FIELDS);
        support.requireFields(payload, "targetType", "targetId");
        TargetType targetType = NoteLinkHandler.linkTargetType(support, payload);
        UUID targetId = support.uuid(payload, "targetId");
        UmlNote note = support.note(context);
        UmlNoteLink link = linkRepository.findByKeyNoteId(note.getId()).stream()
                .filter(candidate -> candidate.getTargetType() == targetType
                        && candidate.getTargetId().equals(targetId))
                .findFirst()
                .orElse(null);
        if (link == null) {
            CommandHandlerSupport.precondition("Note " + note.getId() + " does not annotate " + targetType + " "
                    + targetId);
        }
        linkRepository.delete(link);
        return Outcome.none();
    }
}
