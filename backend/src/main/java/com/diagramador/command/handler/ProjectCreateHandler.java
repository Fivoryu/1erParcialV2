package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Creates the project aggregate and its initial class view. */
@Component
public class ProjectCreateHandler implements CommandHandler {

    private static final Set<String> FIELDS = Set.of("name", "description", "modelName", "defaultViewName");

    private final CommandHandlerSupport support;

    public ProjectCreateHandler(CommandHandlerSupport support) {
        this.support = support;
    }

    @Override
    public String type() {
        return "project.create";
    }

    @Override
    public boolean createsProjectAndModel() {
        return true;
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        support.requireFields(payload, "name");
        String name = support.text(payload, "name");
        String description = support.optionalText(payload, "description");
        String modelName = support.optionalNonBlankText(payload, "modelName");
        String defaultViewName = support.optionalNonBlankText(payload, "defaultViewName");
        if (modelName == null) {
            modelName = name;
        }
        if (defaultViewName == null) {
            defaultViewName = "Class Diagram";
        }

        if (context.project() != null || context.model() != null) {
            CommandHandlerSupport.precondition("Project " + context.envelope().projectId() + " already exists");
        }

        context.entityManager().createNativeQuery("""
                INSERT INTO diagrammer.project
                    (project_id, owner_user_id, name, description, created_at, updated_at)
                VALUES (CAST(:projectId AS uuid), CAST(:ownerId AS uuid), :name, :description, now(), now())
                """)
                .setParameter("projectId", context.envelope().projectId())
                .setParameter("ownerId", context.envelope().author().userId())
                .setParameter("name", name)
                .setParameter("description", description)
                .executeUpdate();

        context.entityManager().createNativeQuery("""
                INSERT INTO diagrammer.project_member
                    (project_id, user_id, role, status, joined_at, updated_at)
                VALUES (CAST(:projectId AS uuid), CAST(:userId AS uuid),
                    CAST('OWNER' AS diagrammer.project_member_role), 'ACTIVE', now(), now())
                """)
                .setParameter("projectId", context.envelope().projectId())
                .setParameter("userId", context.envelope().author().userId())
                .executeUpdate();

        context.entityManager().createNativeQuery("""
                INSERT INTO diagrammer.uml_model
                    (model_id, project_id, name, current_revision, created_at)
                VALUES (CAST(:modelId AS uuid), CAST(:projectId AS uuid), :name, 0, now())
                """)
                .setParameter("modelId", context.envelope().modelId())
                .setParameter("projectId", context.envelope().projectId())
                .setParameter("name", modelName)
                .executeUpdate();

        context.entityManager().createNativeQuery("""
                INSERT INTO diagrammer.diagram_view
                    (model_id, name, view_kind, is_default, created_at)
                VALUES (CAST(:modelId AS uuid), :name, 'CLASS', TRUE, now())
                """)
                .setParameter("modelId", context.envelope().modelId())
                .setParameter("name", defaultViewName)
                .executeUpdate();

        return Outcome.none();
    }
}
