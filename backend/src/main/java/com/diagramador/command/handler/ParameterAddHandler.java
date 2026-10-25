package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlOperation;
import com.diagramador.persistence.OperationParameterRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ParameterAddHandler implements CommandHandler.AddAction {

    private static final Set<String> FIELDS = Set.of("id", "name", "type", "direction", "ordinal");

    private final CommandHandlerSupport support;
    private final OperationParameterRepository parameterRepository;

    public ParameterAddHandler(CommandHandlerSupport support, OperationParameterRepository parameterRepository) {
        this.support = support;
        this.parameterRepository = parameterRepository;
    }

    @Override
    public String type() {
        return "parameter.add";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        support.requireFields(payload, "id", "name", "type");
        UUID id = support.uuid(payload, "id");
        support.duplicateId(parameterRepository.existsById(id), id, "parameter");
        UmlOperation operation = support.operation(context);
        TypeReference type = support.typeReference(context, payload.get("type"));
        String direction = payload.has("direction") && !payload.get("direction").isNull()
                ? support.text(payload, "direction").toUpperCase(java.util.Locale.ROOT) : "IN";
        if (!direction.equals("IN") && !direction.equals("OUT") && !direction.equals("INOUT")) {
            support.invalid("Unsupported parameter direction '" + direction + "'");
        }
        int ordinal = support.ordinalOrNext(payload, "ordinal",
                parameterRepository.findByOperationId(operation.getId()).stream()
                        .map(OperationParameter::getOrdinal).toList());
        support.insertParameter(context, id, operation, type, support.text(payload, "name"), direction, ordinal);
        return Outcome.none();
    }
}
