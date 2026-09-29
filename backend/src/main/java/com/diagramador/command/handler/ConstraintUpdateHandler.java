package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.command.CommandValidationException;
import com.diagramador.command.ErrorCode;
import com.diagramador.domain.SeverityKind;
import com.diagramador.domain.UmlConstraint;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ConstraintUpdateHandler implements CommandHandler {

    private static final Set<String> FIELDS = Set.of("expression", "severity");

    private final CommandHandlerSupport support;

    public ConstraintUpdateHandler(CommandHandlerSupport support) {
        this.support = support;
    }

    @Override
    public String type() {
        return "constraint.update";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        if (payload.isEmpty()) {
            support.invalid("At least one constraint field must be supplied for update");
        }

        UmlConstraint constraint = support.constraint(context);
        if (payload.has("expression")) {
            constraint.setExpression(support.text(payload, "expression"));
        }
        if (payload.has("severity")) {
            constraint.setSeverity(severity(payload.get("severity")));
        }
        return Outcome.none();
    }

    private SeverityKind severity(JsonNode valueNode) {
        String value = valueNode == null || valueNode.isNull()
                ? "null"
                : valueNode.isTextual() ? valueNode.asText() : valueNode.toString();
        try {
            return SeverityKind.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new CommandValidationException(ErrorCode.INVALID_PAYLOAD,
                    "Unsupported constraint severity '" + value + "'");
        }
    }
}
