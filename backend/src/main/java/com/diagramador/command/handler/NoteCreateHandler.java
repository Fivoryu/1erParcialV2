package com.diagramador.command.handler;

import com.diagramador.command.CommandHandler;
import com.diagramador.domain.TargetType;
import com.diagramador.persistence.UmlNoteRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Creates a UML note with the elements it annotates; the id is client-assigned like every other create. */
@Component
public class NoteCreateHandler implements CommandHandler.AddAction {

    private static final Set<String> FIELDS = Set.of("id", "body", "annotatedElementIds");

    private final CommandHandlerSupport support;
    private final UmlNoteRepository noteRepository;

    public NoteCreateHandler(CommandHandlerSupport support, UmlNoteRepository noteRepository) {
        this.support = support;
        this.noteRepository = noteRepository;
    }

    @Override
    public String type() {
        return "note.create";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        support.requireFields(payload, "body");
        UUID id = payload.hasNonNull("id") ? support.uuid(payload, "id") : UUID.randomUUID();
        support.duplicateId(noteRepository.existsById(id), id, "note");
        String body = support.noteBody(payload, "body");
        Set<UUID> elementIds = annotatedElementIds(payload);

        List<TargetType> types = new ArrayList<>();
        for (UUID elementId : elementIds) {
            types.add(support.annotatedElementType(context.model(), elementId));
        }
        support.insertNote(context, id, body);
        int index = 0;
        for (UUID elementId : elementIds) {
            support.insertNoteLink(context, id, types.get(index++), elementId);
        }
        return Outcome.none();
    }

    private Set<UUID> annotatedElementIds(JsonNode payload) {
        Set<UUID> result = new LinkedHashSet<>();
        JsonNode ids = payload.get("annotatedElementIds");
        if (ids == null || ids.isNull()) {
            return result;
        }
        if (!ids.isArray()) {
            CommandHandlerSupport.invalid("Payload field 'annotatedElementIds' must be a list of element ids");
        }
        for (JsonNode element : ids) {
            UUID elementId = null;
            if (element.isTextual()) {
                try {
                    elementId = UUID.fromString(element.asText());
                } catch (IllegalArgumentException exception) {
                    elementId = null;
                }
            }
            if (elementId == null) {
                CommandHandlerSupport.invalid("Payload field 'annotatedElementIds' must contain only UUID strings");
            }
            if (!result.add(elementId)) {
                CommandHandlerSupport.invalid("Payload field 'annotatedElementIds' lists " + elementId + " twice");
            }
        }
        return result;
    }
}
