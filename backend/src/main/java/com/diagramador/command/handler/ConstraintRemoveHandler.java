package com.diagramador.command.handler;

import com.diagramador.command.CommandHandler;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.UmlConstraint;
import com.diagramador.persistence.UmlConstraintRepository;
import org.springframework.stereotype.Component;

@Component
public class ConstraintRemoveHandler implements CommandHandler.DeleteAction {

    private final CommandHandlerSupport support;
    private final UmlConstraintRepository constraintRepository;

    public ConstraintRemoveHandler(CommandHandlerSupport support, UmlConstraintRepository constraintRepository) {
        this.support = support;
        this.constraintRepository = constraintRepository;
    }

    @Override
    public String type() {
        return "constraint.remove";
    }

    @Override
    public Outcome apply(Context context) {
        support.noPayload(context);
        UmlConstraint constraint = support.constraint(context);
        constraintRepository.delete(constraint);
        return Outcome.deleted(TargetType.CONSTRAINT, constraint.getId());
    }
}
