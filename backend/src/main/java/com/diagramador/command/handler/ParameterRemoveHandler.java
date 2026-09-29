package com.diagramador.command.handler;

import com.diagramador.command.CommandHandler;
import com.diagramador.persistence.OperationParameterRepository;
import org.springframework.stereotype.Component;

@Component
public class ParameterRemoveHandler implements CommandHandler {

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
    public void apply(Context context) {
        support.noPayload(context);
        parameterRepository.delete(support.parameter(context));
    }
}
