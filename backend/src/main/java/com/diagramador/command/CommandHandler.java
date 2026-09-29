package com.diagramador.command;

import com.diagramador.domain.Project;
import com.diagramador.domain.UmlModel;
import jakarta.persistence.EntityManager;

/** Port implemented by each registered command type. */
public interface CommandHandler {

    String type();

    void apply(Context context);

    record Context(OperationEnvelope envelope, Project project, UmlModel model, EntityManager entityManager) {
    }
}
