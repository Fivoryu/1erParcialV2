package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.TypeReference;
import com.diagramador.domain.UmlOperation;
import com.diagramador.persistence.UmlOperationRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OperationAddHandler implements CommandHandler.AddAction {

    private static final Set<String> FIELDS = Set.of("id", "name", "returnType", "visibility", "isAbstract",
            "isStatic", "ordinal");

    private final CommandHandlerSupport support;
    private final UmlOperationRepository operationRepository;

    public OperationAddHandler(CommandHandlerSupport support, UmlOperationRepository operationRepository) {
        this.support = support;
        this.operationRepository = operationRepository;
    }

    @Override
    public String type() {
        return "operation.add";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        support.requireFields(payload, "id", "name");
        UUID id = support.uuid(payload, "id");
        support.duplicateId(operationRepository.existsById(id), id, "operation");
        Classifier classifier = support.classifier(context, "classifier");
        String name = support.text(payload, "name");
        support.ensureOperationNameAvailable(classifier, name, null);
        TypeReference returnType = payload.has("returnType") && !payload.get("returnType").isNull()
                ? support.typeReference(context, payload.get("returnType")) : null;
        String visibility = support.visibility(payload, "visibility", "PUBLIC");
        boolean isAbstract = payload.has("isAbstract") ? support.bool(payload, "isAbstract") : false;
        boolean isStatic = payload.has("isStatic") ? support.bool(payload, "isStatic") : false;
        int ordinal = support.ordinalOrNext(payload, "ordinal",
                operationRepository.findByClassifierId(classifier.getId()).stream().map(UmlOperation::getOrdinal).toList());
        support.insertOperation(context, id, classifier, name, returnType, visibility,
                isAbstract, isStatic, ordinal);
        return Outcome.none();
    }
}
