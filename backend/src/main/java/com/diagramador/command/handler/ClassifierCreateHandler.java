package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.Classifier;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.persistence.ClassifierRepository;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ClassifierCreateHandler implements CommandHandler.AddAction {

    private static final Set<String> FIELDS = Set.of("id", "name", "kind", "namespaceId", "stereotype");

    private final CommandHandlerSupport support;
    private final ClassifierRepository classifierRepository;

    public ClassifierCreateHandler(CommandHandlerSupport support, ClassifierRepository classifierRepository) {
        this.support = support;
        this.classifierRepository = classifierRepository;
    }

    @Override
    public String type() {
        return "classifier.create";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        support.requireFields(payload, "id", "name", "kind");
        java.util.UUID id = support.uuid(payload, "id");
        support.duplicateId(classifierRepository.existsById(id), id, "classifier");
        String name = support.text(payload, "name");
        ClassifierKind kind = support.classifierKind(payload);
        Classifier namespace = support.namespace(context.model(), support.optionalUuid(payload, "namespaceId"));
        support.ensureClassifierNameAvailable(context.model(), namespace, name, null);
        boolean isAbstract = kind == ClassifierKind.ABSTRACT_CLASS;
        String stereotype = support.classifierStereotype(payload, "stereotype", kind);
        support.insertClassifier(context, id, namespace, kind, name, isAbstract, stereotype);
        return Outcome.none();
    }
}
