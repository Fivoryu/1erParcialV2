package com.diagramador.assistant;

import com.diagramador.api.ModelResource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Renders the semantic JSON snapshot of one model that both the plan flow and the collaborative
 * chat (T3.1, S8-lite) send to the AI provider. Extracted from AssistantPlanService so the two
 * callers share one rendering and stay in sync; real token budgeting stays in T6 (PD-03).
 */
@Component
public class DiagramContextRenderer {

    private final ObjectMapper objectMapper;

    public DiagramContextRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String render(ModelResource model) {
        Map<String, Object> context = new LinkedHashMap<>();
        List<Map<String, Object>> classifiers = new ArrayList<>();
        for (ModelResource.ClassifierResource classifier : model.classifiers()) {
            Map<String, Object> classifierJson = new LinkedHashMap<>();
            classifierJson.put("name", classifier.name());
            classifierJson.put("kind", classifier.kind() == null ? null : classifier.kind().name());
            List<Map<String, Object>> attributes = new ArrayList<>();
            for (ModelResource.AttributeResource attribute : classifier.attributes()) {
                Map<String, Object> attributeJson = new LinkedHashMap<>();
                attributeJson.put("name", attribute.name());
                attributeJson.put("type", attribute.type());
                attributes.add(attributeJson);
            }
            classifierJson.put("attributes", attributes);
            List<Map<String, Object>> operations = new ArrayList<>();
            for (ModelResource.OperationResource operation : classifier.operations()) {
                Map<String, Object> operationJson = new LinkedHashMap<>();
                operationJson.put("name", operation.name());
                operationJson.put("returnType", operation.returnType());
                operations.add(operationJson);
            }
            classifierJson.put("operations", operations);
            classifiers.add(classifierJson);
        }
        context.put("classifiers", classifiers);

        Map<UUID, String> classifierNames = new LinkedHashMap<>();
        for (ModelResource.ClassifierResource classifier : model.classifiers()) {
            classifierNames.put(classifier.id(), classifier.name());
        }
        List<Map<String, Object>> relationships = new ArrayList<>();
        for (ModelResource.RelationshipResource relationship : model.relationships()) {
            Map<String, Object> relationshipJson = new LinkedHashMap<>();
            relationshipJson.put("kind", relationship.kind());
            String source = relationship.ends().size() > 0
                    ? classifierNames.get(relationship.ends().get(0).classifierId()) : null;
            String target = relationship.ends().size() > 1
                    ? classifierNames.get(relationship.ends().get(1).classifierId()) : null;
            relationshipJson.put("source", source);
            relationshipJson.put("target", target);
            relationships.add(relationshipJson);
        }
        context.put("relationships", relationships);
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("The model context could not be serialized", exception);
        }
    }
}
