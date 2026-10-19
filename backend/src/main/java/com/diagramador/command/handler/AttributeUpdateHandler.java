package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.UmlAttribute;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AttributeUpdateHandler implements CommandHandler {

    private static final Set<String> FIELDS = Set.of("name", "type", "visibility", "multiplicity", "defaultValue",
            "isStatic", "isAbstract");

    private final CommandHandlerSupport support;

    public AttributeUpdateHandler(CommandHandlerSupport support) {
        this.support = support;
    }

    @Override
    public String type() {
        return "attribute.update";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        rejectUnsupportedFlags(payload);
        if (payload.isEmpty()) {
            support.invalid("At least one attribute field must be supplied for update");
        }
        UmlAttribute attribute = support.attribute(context);
        boolean literal = attribute.getClassifier().getKind() == ClassifierKind.ENUMERATION;
        if (payload.has("name")) {
            String name = support.text(payload, "name");
            if (literal) {
                support.ensureLiteral(attribute.getClassifier(), name, false);
            }
            support.ensureAttributeNameAvailable(attribute.getClassifier(), name, attribute.getId());
            attribute.setName(name);
        }
        if (payload.has("type")) {
            if (literal) {
                support.ensureLiteral(attribute.getClassifier(), attribute.getName(), true);
            }
            if (payload.get("type").isNull()) {
                support.invalid("Attribute type cannot be null");
            }
            attribute.setTypeReference(support.typeReference(context, payload.get("type")));
        }
        if (payload.has("visibility")) {
            attribute.setVisibility(support.visibility(payload, "visibility", attribute.getVisibility()));
        }
        if (payload.has("multiplicity")) {
            attribute.setMultiplicity(support.multiplicity(payload, "multiplicity", attribute.getMultiplicity()));
        }
        if (payload.has("defaultValue")) {
            attribute.setDefaultValue(support.optionalText(payload, "defaultValue"));
        }
        return Outcome.none();
    }

    private void rejectUnsupportedFlags(JsonNode payload) {
        if (payload.has("isStatic")) {
            support.precondition("Attribute isStatic is unsupported: BL-FEA-05");
        }
        if (payload.has("isAbstract")) {
            support.precondition("Attribute isAbstract is unsupported: BL-FEA-06");
        }
    }
}
