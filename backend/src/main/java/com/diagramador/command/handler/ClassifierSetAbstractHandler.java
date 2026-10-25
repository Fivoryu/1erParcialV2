package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ClassifierSetAbstractHandler implements CommandHandler.AddAction {

    private static final Set<String> FIELDS = Set.of("isAbstract");

    private final CommandHandlerSupport support;

    public ClassifierSetAbstractHandler(CommandHandlerSupport support) {
        this.support = support;
    }

    @Override
    public String type() {
        return "classifier.setAbstract";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        support.requireFields(payload, "isAbstract");
        Classifier classifier = support.classifier(context, "classifier");
        if (classifier.getKind() != ClassifierKind.CLASS && classifier.getKind() != ClassifierKind.ABSTRACT_CLASS) {
            support.precondition("setAbstract is meaningless for classifier kind " + classifier.getKind());
        }
        classifier.setAbstract(support.bool(payload, "isAbstract"));
        return Outcome.none();
    }
}
