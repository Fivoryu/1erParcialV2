package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ClassifierSetNamespaceHandler implements CommandHandler.AddAction {

    private static final Set<String> FIELDS = Set.of("namespaceId");

    private final CommandHandlerSupport support;

    public ClassifierSetNamespaceHandler(CommandHandlerSupport support) {
        this.support = support;
    }

    @Override
    public String type() {
        return "classifier.setNamespace";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        if (!payload.has("namespaceId")) {
            support.invalid("Payload field 'namespaceId' is required");
        }

        Classifier classifier = support.classifier(context, "classifier");
        UUID namespaceId = support.optionalUuid(payload, "namespaceId");
        if (namespaceId == null) {
            classifier.setNamespace(null);
            return Outcome.none();
        }
        if (namespaceId.equals(classifier.getId())) {
            support.precondition("Classifier " + namespaceId + " cannot be its own namespace");
        }

        Classifier namespace = support.classifier(context.model(), namespaceId);
        if (namespace.getKind() != ClassifierKind.PACKAGE) {
            support.precondition("Namespace classifier " + namespaceId + " must be a PACKAGE");
        }

        Set<UUID> visited = new HashSet<>();
        for (Classifier ancestor = namespace; ancestor != null; ancestor = ancestor.getNamespace()) {
            UUID ancestorId = ancestor.getId();
            if (ancestorId.equals(classifier.getId())) {
                support.precondition("Setting namespace would create a cycle through classifier " + ancestorId);
            }
            if (!visited.add(ancestorId)) {
                support.precondition("Namespace chain already contains a cycle through classifier " + ancestorId);
            }
        }

        classifier.setNamespace(namespace);
        return Outcome.none();
    }
}
