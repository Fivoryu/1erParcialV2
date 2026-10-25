package com.diagramador.command.handler;

import com.diagramador.command.CommandHandler;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.UmlOperation;
import com.diagramador.persistence.UmlOperationRepository;
import org.springframework.stereotype.Component;

@Component
public class OperationRemoveHandler implements CommandHandler.DeleteAction {

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
    public Outcome apply(Context context) {
        support.noPayload(context);
        UmlOperation operation = support.operation(context);
        operationRepository.delete(operation);
        return Outcome.deleted(TargetType.OPERATION, operation.getId());
    }
}
