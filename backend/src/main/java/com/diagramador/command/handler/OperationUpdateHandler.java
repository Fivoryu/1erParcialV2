package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.OperationStereotype;
import com.diagramador.domain.UmlOperation;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class OperationUpdateHandler implements CommandHandler.AddAction {

    private static final Set<String> FIELDS = Set.of("name", "returnType", "visibility", "isAbstract", "isStatic", "stereotype");

    private final CommandHandlerSupport support;

    public OperationUpdateHandler(CommandHandlerSupport support) {
        this.support = support;
    }

    @Override
    public String type() {
        return "operation.update";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        if (payload.isEmpty()) {
            support.invalid("At least one operation field must be supplied for update");
        }
        UmlOperation operation = support.operation(context);
        if (payload.has("name")) {
            String name = support.text(payload, "name");
            support.ensureOperationNameAvailable(operation.getClassifier(), name, operation.getId());
            operation.setName(name);
        }
        if (payload.has("returnType")) {
            operation.setReturnTypeReference(payload.get("returnType").isNull()
                    ? null : support.typeReference(context, payload.get("returnType")));
        }
        if (payload.has("visibility")) {
            operation.setVisibility(support.visibility(payload, "visibility", operation.getVisibility()));
        }
        if (payload.has("isAbstract")) {
            operation.setAbstract(support.bool(payload, "isAbstract"));
        }
        if (payload.has("isStatic")) {
            operation.setStatic(support.bool(payload, "isStatic"));
        }
        if (payload.has("stereotype")) {
            operation.setStereotype(support.stereotype(payload, "stereotype", OperationStereotype.class));
        }
        return Outcome.none();
    }
}
