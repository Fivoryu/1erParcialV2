package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.PersistenceStore;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Fixes the persistence store of a class, or hands the choice back to the automatic classifier with null. */
@Component
public class ClassifierSetStoreHandler implements CommandHandler.AddAction {

    private static final Set<String> FIELDS = Set.of("store");

    private final CommandHandlerSupport support;

    public ClassifierSetStoreHandler(CommandHandlerSupport support) {
        this.support = support;
    }

    @Override
    public String type() {
        return "classifier.setStore";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        if (!payload.has("store")) {
            support.requireFields(payload, "store");
        }
        Classifier classifier = support.classifier(context, "classifier");
        if (classifier.getKind() != ClassifierKind.CLASS && classifier.getKind() != ClassifierKind.ABSTRACT_CLASS) {
            support.precondition("setStore is meaningless for classifier kind " + classifier.getKind());
        }
        classifier.setStoreOverride(parse(support.optionalNonBlankText(payload, "store")));
        return Outcome.none();
    }

    private PersistenceStore parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return PersistenceStore.valueOf(value);
        } catch (IllegalArgumentException exception) {
            support.precondition("Payload field 'store' must be SQL, DOCUMENT or null");
            return null;
        }
    }
}
