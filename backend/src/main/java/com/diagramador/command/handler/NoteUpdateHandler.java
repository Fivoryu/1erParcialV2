package com.diagramador.command.handler;

import com.diagramador.command.CommandHandler;
import com.diagramador.domain.UmlNote;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Replaces the body of the targeted note. */
@Component
public class NoteUpdateHandler implements CommandHandler.AddAction {

    private static final Set<String> FIELDS = Set.of("body");

    private final CommandHandlerSupport support;

    public NoteUpdateHandler(CommandHandlerSupport support) {
        this.support = support;
    }

    @Override
    public String type() {
        return "note.update";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        support.requireFields(payload, "body");
        String body = support.noteBody(payload, "body");
        UmlNote note = support.note(context);
        note.setBody(body);
        return Outcome.none();
    }
}
