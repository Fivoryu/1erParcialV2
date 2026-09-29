package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.Project;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Renames an existing project. */
@Component
public class ProjectRenameHandler implements CommandHandler {

    private static final Set<String> FIELDS = Set.of("name");

    private final CommandHandlerSupport support;

    public ProjectRenameHandler(CommandHandlerSupport support) {
        this.support = support;
    }

    @Override
    public String type() {
        return "project.rename";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        support.requireFields(payload, "name");
        Project project = context.project();
        if (!support.targetId(context, "project").equals(project.getId())) {
            CommandHandlerSupport.precondition("Target project does not match envelope project "
                    + context.envelope().projectId());
        }
        project.setName(support.text(payload, "name"));
        return Outcome.none();
    }
}
