package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.persistence.DiagramViewRepository;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Creates a shared diagram view in the envelope's model. */
@Component
public class ViewCreateHandler implements CommandHandler {

    private static final Set<String> FIELDS = Set.of("id", "name", "viewKind");

    private final CommandHandlerSupport support;
    private final DiagramViewRepository viewRepository;

    public ViewCreateHandler(CommandHandlerSupport support, DiagramViewRepository viewRepository) {
        this.support = support;
        this.viewRepository = viewRepository;
    }

    @Override
    public String type() {
        return "view.create";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        support.requireFields(payload, "id", "name");
        UUID id = support.uuid(payload, "id");
        String name = support.text(payload, "name");
        String requestedViewKind = support.optionalNonBlankText(payload, "viewKind");
        String viewKind = requestedViewKind == null ? "CLASS" : requestedViewKind.toUpperCase(Locale.ROOT);
        if (!viewKind.equals("CLASS")) {
            CommandHandlerSupport.precondition("Unknown viewKind '" + requestedViewKind + "'");
        }
        support.duplicateId(viewRepository.existsById(id), id, "view");

        context.entityManager().createNativeQuery("""
                INSERT INTO diagrammer.diagram_view
                    (view_id, model_id, name, view_kind, is_default, created_at)
                VALUES (CAST(:viewId AS uuid), CAST(:modelId AS uuid), :name, :viewKind, FALSE, now())
                """)
                .setParameter("viewId", id)
                .setParameter("modelId", context.model().getId())
                .setParameter("name", name)
                .setParameter("viewKind", viewKind)
                .executeUpdate();

        return Outcome.none();
    }
}
