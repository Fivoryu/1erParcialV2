package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.persistence.UmlAttributeRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AttributeAddHandler implements CommandHandler {

    private static final Set<String> FIELDS = Set.of("id", "name", "type", "visibility", "multiplicity",
            "defaultValue", "ordinal", "isStatic", "isAbstract");

    private final CommandHandlerSupport support;
    private final UmlAttributeRepository attributeRepository;

    public AttributeAddHandler(CommandHandlerSupport support, UmlAttributeRepository attributeRepository) {
        this.support = support;
        this.attributeRepository = attributeRepository;
    }

    @Override
    public String type() {
        return "attribute.add";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        rejectUnsupportedFlags(payload);
        Classifier classifier = support.classifier(context, "classifier");
        boolean literal = classifier.getKind() == ClassifierKind.ENUMERATION;
        if (literal) {
            support.requireFields(payload, "id", "name");
        } else {
            support.requireFields(payload, "id", "name", "type");
        }
        UUID id = support.uuid(payload, "id");
        support.duplicateId(attributeRepository.existsById(id), id, "attribute");
        String name = support.text(payload, "name");
        if (literal) {
            support.ensureLiteral(classifier, name, payload.hasNonNull("type"));
        }
        support.ensureAttributeNameAvailable(classifier, name, null);
        TypeReference type = literal ? null : support.typeReference(context, payload.get("type"));
        String visibility = support.visibility(payload, "visibility", "PUBLIC");
        MultiplicityKind multiplicity = support.multiplicity(payload, "multiplicity", MultiplicityKind.EXACTLY_ONE);
        int ordinal = support.ordinalOrNext(payload, "ordinal",
                attributeRepository.findByClassifierId(classifier.getId()).stream().map(UmlAttribute::getOrdinal).toList());
        support.insertAttribute(context, id, classifier, type, name, visibility, multiplicity,
                support.optionalText(payload, "defaultValue"), ordinal);
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
