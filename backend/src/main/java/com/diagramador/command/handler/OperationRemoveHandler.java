package com.diagramador.command.handler;

import com.diagramador.command.CommandHandler;
import com.diagramador.persistence.UmlOperationRepository;
import org.springframework.stereotype.Component;

@Component
public class OperationRemoveHandler implements CommandHandler {

    private final CommandHandlerSupport support;
    private final UmlOperationRepository operationRepository;

    public OperationRemoveHandler(CommandHandlerSupport support, UmlOperationRepository operationRepository) {
        this.support = support;
        this.operationRepository = operationRepository;
    }

    @Override
    public String type() {
        return "operation.remove";
    }

    @Override
    public void apply(Context context) {
        support.noPayload(context);
        operationRepository.delete(support.operation(context));
    }
}
