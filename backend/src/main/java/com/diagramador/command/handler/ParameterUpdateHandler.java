package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.OperationParameter;
import com.diagramador.domain.TypeReference;
import com.diagramador.persistence.OperationParameterRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ParameterUpdateHandler implements CommandHandler.AddAction {

    private static final Set<String> FIELDS = Set.of("name", "type", "direction", "ordinal", "operationId");
    private static final Set<String> DIRECTIONS = Set.of("IN", "OUT", "INOUT");

    private final CommandHandlerSupport support;
    private final OperationParameterRepository parameterRepository;

    public ParameterUpdateHandler(CommandHandlerSupport support, OperationParameterRepository parameterRepository) {
        this.support = support;
        this.parameterRepository = parameterRepository;
    }

    @Override
    public String type() {
        return "parameter.update";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        if (payload.isEmpty()) {
            support.invalid("At least one parameter field must be supplied for update");
        }

        OperationParameter parameter = support.parameter(context);
        if (payload.has("operationId")) {
            support.precondition("The owning operation of parameter " + parameter.getId() + " cannot be changed");
        }
        if (payload.has("name")) {
            parameter.setName(support.text(payload, "name"));
        }
        if (payload.has("type")) {
            if (payload.get("type").isNull()) {
                support.invalid("Parameter type cannot be null");
            }
            TypeReference type = support.typeReference(context, payload.get("type"));
            parameter.setTypeReference(type);
        }
        if (payload.has("direction")) {
            parameter.setDirection(direction(payload));
        }
        if (payload.has("ordinal")) {
            moveToPosition(context, parameter, support.ordinal(payload, "ordinal"));
        }
        return Outcome.none();
    }

    private String direction(JsonNode payload) {
        String value = support.text(payload, "direction").toUpperCase(Locale.ROOT);
        if (!DIRECTIONS.contains(value)) {
            support.invalid("Unsupported parameter direction '" + value + "'");
        }
        return value;
    }

    private void moveToPosition(Context context, OperationParameter parameter, int position) {
        List<OperationParameter> ordered = new ArrayList<>(parameterRepository.findByOperationId(
                parameter.getOperation().getId()));
        ordered.sort(Comparator.comparingInt(OperationParameter::getOrdinal)
                .thenComparing(OperationParameter::getId));
        if (position >= ordered.size()) {
            support.invalid("Payload field 'ordinal' must be a parameter position between 0 and "
                    + (ordered.size() - 1));
        }

        ordered.remove(parameter);
        ordered.add(position, parameter);
        List<Integer> temporaryOrdinals = temporaryOrdinals(ordered);
        for (int index = 0; index < ordered.size(); index++) {
            ordered.get(index).setOrdinal(temporaryOrdinals.get(index));
        }
        context.entityManager().flush();
        for (int index = 0; index < ordered.size(); index++) {
            ordered.get(index).setOrdinal(index);
        }
    }

    private List<Integer> temporaryOrdinals(List<OperationParameter> parameters) {
        Set<Integer> occupied = new HashSet<>();
        parameters.forEach(parameter -> occupied.add(parameter.getOrdinal()));
        List<Integer> temporary = new ArrayList<>(parameters.size());
        long candidate = parameters.size();
        while (temporary.size() < parameters.size() && candidate <= Integer.MAX_VALUE) {
            int ordinal = (int) candidate;
            if (!occupied.contains(ordinal)) {
                occupied.add(ordinal);
                temporary.add(ordinal);
            }
            candidate++;
        }
        if (temporary.size() != parameters.size()) {
            support.invalid("Parameter ordinals cannot be safely renumbered");
        }
        return temporary;
    }
}
