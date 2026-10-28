package com.diagramador.assistant;

import java.util.Set;

public final class AssistantPlanCatalog {

    public static final Set<String> OPERATION_TYPES = Set.of(
            "classifier.create",
            "classifier.rename",
            "classifier.delete",
            "classifier.setAbstract",
            "classifier.move",
            "attribute.add",
            "attribute.update",
            "attribute.remove",
            "operation.add",
            "relationship.create",
            "relationship.update",
            "relationship.delete");

    private AssistantPlanCatalog() {
    }

    public static boolean contains(String type) {
        return OPERATION_TYPES.contains(type);
    }
}
