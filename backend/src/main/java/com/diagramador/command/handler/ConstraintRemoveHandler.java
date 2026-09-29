package com.diagramador.command.handler;

import com.diagramador.command.CommandHandler;
import com.diagramador.persistence.UmlConstraintRepository;
import org.springframework.stereotype.Component;

@Component
public class ConstraintRemoveHandler implements CommandHandler {

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
    public void apply(Context context) {
        support.noPayload(context);
        constraintRepository.delete(support.constraint(context));
    }
}
