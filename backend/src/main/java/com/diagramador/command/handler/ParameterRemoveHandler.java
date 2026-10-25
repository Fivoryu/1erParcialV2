package com.diagramador.command.handler;

import com.diagramador.command.CommandHandler;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.TargetType;
import com.diagramador.persistence.OperationParameterRepository;
import org.springframework.stereotype.Component;

@Component
public class ParameterRemoveHandler implements CommandHandler.DeleteAction {

    private final CommandHandlerSupport support;
    private final OperationParameterRepository parameterRepository;

    public ParameterRemoveHandler(CommandHandlerSupport support, OperationParameterRepository parameterRepository) {
        this.support = support;
        this.parameterRepository = parameterRepository;
    }

    @Override
    public String type() {
        return "parameter.remove";
    }

    @Override
    public Outcome apply(Context context) {
        support.noPayload(context);
        OperationParameter parameter = support.parameter(context);
        parameterRepository.delete(parameter);
        return Outcome.deleted(TargetType.PARAMETER, parameter.getId());
    }
}
