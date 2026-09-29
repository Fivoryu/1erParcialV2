package com.diagramador.command;

import com.diagramador.domain.Project;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.UmlModel;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Port implemented by each registered command type. */
public interface CommandHandler {

    String type();

    Outcome apply(Context context);

    record Context(OperationEnvelope envelope, Project project, UmlModel model, EntityManager entityManager) {
    }

    record DeletedTarget(TargetType targetType, UUID targetId) {
    }

    /** The handler declares physical deletions; the service persists their history after the command row exists. */
    record Outcome(List<DeletedTarget> deletedTargets) {
        public Outcome {
            deletedTargets = List.copyOf(new LinkedHashSet<>(deletedTargets));
        }

        public static Outcome none() {
            return new Outcome(List.of());
        }

        public static Outcome deleted(TargetType targetType, UUID targetId) {
            return new Outcome(List.of(new DeletedTarget(targetType, targetId)));
        }
    }
}
