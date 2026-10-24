package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.Classifier;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ClassifierRenameHandler implements CommandHandler.AddAction {

    private static final Set<String> FIELDS = Set.of("name");

    private final CommandHandlerSupport support;

    public ClassifierRenameHandler(CommandHandlerSupport support) {
        this.support = support;
    }

    @Override
    public String type() {
        return "classifier.rename";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        support.requireFields(payload, "name");
        Classifier classifier = support.classifier(context, "classifier");
        String name = support.text(payload, "name");
        support.ensureClassifierNameAvailable(context.model(), classifier.getNamespace(), name, classifier.getId());
        classifier.setName(name);
        return Outcome.none();
    }
}
