package com.diagramador.command.handler;

import com.diagramador.command.CommandHandler;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.UmlNote;
import com.diagramador.persistence.UmlNoteLinkRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adds one annotated element (a classifier or a relationship of the model) to a note. */
@Component
public class NoteLinkHandler implements CommandHandler.AddAction {

    static final Set<String> FIELDS = Set.of("targetType", "targetId");

    private final CommandHandlerSupport support;
    private final UmlNoteLinkRepository linkRepository;

    public NoteLinkHandler(CommandHandlerSupport support, UmlNoteLinkRepository linkRepository) {
        this.support = support;
        this.linkRepository = linkRepository;
    }

    @Override
    public String type() {
        return "note.link";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        support.requireFields(payload, "targetType", "targetId");
        TargetType targetType = linkTargetType(support, payload);
        UUID targetId = support.uuid(payload, "targetId");
        UmlNote note = support.note(context);
        TargetType actual = support.annotatedElementType(context.model(), targetId);
        if (actual != targetType) {
            CommandHandlerSupport.precondition("Element " + targetId + " is a " + actual + ", not a " + targetType);
        }
        boolean linked = linkRepository.findByKeyNoteId(note.getId()).stream()
                .anyMatch(link -> link.getTargetType() == targetType && link.getTargetId().equals(targetId));
        if (linked) {
            CommandHandlerSupport.precondition("Note " + note.getId() + " already annotates " + targetType + " "
                    + targetId);
        }
        support.insertNoteLink(context, note.getId(), targetType, targetId);
        return Outcome.none();
    }

    /** The annotated element kind of a link payload: CLASSIFIER or RELATIONSHIP, case ignored. */
    static TargetType linkTargetType(CommandHandlerSupport support, JsonNode payload) {
        String value = support.text(payload, "targetType");
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "CLASSIFIER" -> TargetType.CLASSIFIER;
            case "RELATIONSHIP" -> TargetType.RELATIONSHIP;
            default -> {
                CommandHandlerSupport.invalid("Unsupported note targetType '" + value
                        + "'; supported values are CLASSIFIER and RELATIONSHIP");
                yield null;
            }
        };
    }
}
