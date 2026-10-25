package com.diagramador.command;

import com.diagramador.domain.Project;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.VcsAction;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port implemented by each registered command type. */
public interface CommandHandler {

    String type();

    /** The version-control action for this handler, or empty when it has not declared one. */
    default Optional<VcsAction> vcsAction() {
        return Optional.empty();
    }

    interface AddAction extends CommandHandler {
        @Override
        default Optional<VcsAction> vcsAction() {
            return Optional.of(VcsAction.ADD);
        }
    }

    interface CreateAction extends CommandHandler {
        @Override
        default Optional<VcsAction> vcsAction() {
            return Optional.of(VcsAction.CREATE);
        }
    }

    interface DeleteAction extends CommandHandler {
        @Override
        default Optional<VcsAction> vcsAction() {
            return Optional.of(VcsAction.DELETE);
        }
    }

    /**
     * Declares that this handler creates the aggregate identified by the envelope.
     * The service uses this contract property to relax only the aggregate-existence
     * and membership checks for the creating command.
     */
    default boolean createsProjectAndModel() {
        return false;
    }

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
