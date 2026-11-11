package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.Classifier;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Sets (or, with a null stereotype, clears) the stereotype of a class from the fixed ClassifierStereotype list. */
@Component
public class ClassifierSetStereotypeHandler implements CommandHandler.AddAction {

    private static final Set<String> FIELDS = Set.of("stereotype");

    private final CommandHandlerSupport support;

    public ClassifierSetStereotypeHandler(CommandHandlerSupport support) {
        this.support = support;
    }

    @Override
    public String type() {
        return "classifier.stereotype.set";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        if (!payload.has("stereotype")) {
            support.requireFields(payload, "stereotype");
        }
        Classifier classifier = support.classifier(context, "classifier");
        classifier.setStereotype(support.classifierStereotype(payload, "stereotype", classifier.getKind()));
        return Outcome.none();
    }
}
