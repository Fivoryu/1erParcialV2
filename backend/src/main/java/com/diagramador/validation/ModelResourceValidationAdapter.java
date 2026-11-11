package com.diagramador.validation;

import com.diagramador.api.ModelResource;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.RelationshipKind;
import com.diagramador.validation.ModelValidationReadModel.AttributeElement;
import com.diagramador.validation.ModelValidationReadModel.ClassifierElement;
import com.diagramador.validation.ModelValidationReadModel.OperationElement;
import com.diagramador.validation.ModelValidationReadModel.ParameterElement;
import com.diagramador.validation.ModelValidationReadModel.RelationshipElement;
import com.diagramador.validation.ModelValidationReadModel.RelationshipEndElement;
import com.diagramador.validation.ModelValidationReadModel.TypeReferenceElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Projects an in-memory model (an XMI reading before it is persisted) into the validation read-model, so the
 * semantic rules can judge what an import would leave behind. Type references follow the import handler's own
 * resolution: the source id, else a unique classifier of that name, else a primitive of that name.
 */
public final class ModelResourceValidationAdapter {

    private ModelResourceValidationAdapter() {
    }

    public static ModelValidationReadModel toReadModel(ModelResource model) {
        Map<UUID, ModelResource.ClassifierResource> classifiersById = new LinkedHashMap<>();
        model.classifiers().forEach(item -> classifiersById.putIfAbsent(item.id(), item));
        Map<UUID, TypeReferenceElement> references = new LinkedHashMap<>();

        List<ClassifierElement> classifiers = model.classifiers().stream()
                .map(item -> new ClassifierElement(item.id(), item.namespaceId(), item.kind(), item.name())).toList();
        List<AttributeElement> attributes = new ArrayList<>();
        List<OperationElement> operations = new ArrayList<>();
        List<ParameterElement> parameters = new ArrayList<>();
        for (ModelResource.ClassifierResource classifier : model.classifiers()) {
            for (ModelResource.AttributeResource attribute : classifier.attributes()) {
                MultiplicityKind multiplicity = multiplicity(attribute.multiplicity());
                attributes.add(new AttributeElement(attribute.id(), classifier.id(),
                        reference(model, classifiersById, references, attribute.typeReferenceId(), attribute.type()),
                        attribute.name(), multiplicity, attribute.ordinal()));
            }
            for (ModelResource.OperationResource operation : classifier.operations()) {
                operations.add(new OperationElement(operation.id(), classifier.id(),
                        reference(model, classifiersById, references, operation.returnTypeReferenceId(),
                                operation.returnType()), operation.name(), operation.ordinal()));
                for (ModelResource.ParameterResource parameter : operation.parameters()) {
                    parameters.add(new ParameterElement(parameter.id(), operation.id(),
                            reference(model, classifiersById, references, parameter.typeReferenceId(),
                                    parameter.type()), parameter.name(), parameter.ordinal()));
                }
            }
        }
        List<RelationshipElement> relationships = new ArrayList<>();
        List<RelationshipEndElement> ends = new ArrayList<>();
        for (ModelResource.RelationshipResource relationship : model.relationships()) {
            RelationshipKind kind;
            try {
                kind = RelationshipKind.valueOf(relationship.kind().toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                continue;
            }
            relationships.add(new RelationshipElement(relationship.id(), kind, relationship.name()));
            for (ModelResource.RelationshipEndResource end : relationship.ends()) {
                MultiplicityKind multiplicity = multiplicity(end.multiplicity());
                ends.add(new RelationshipEndElement(end.id(), relationship.id(), end.classifierId(), end.ordinal(),
                        end.role(), multiplicity));
            }
        }
        return new ModelValidationReadModel(classifiers, relationships, ends, attributes, operations, parameters,
                new ArrayList<>(references.values()));
    }

    private static UUID reference(ModelResource model, Map<UUID, ModelResource.ClassifierResource> classifiersById,
            Map<UUID, TypeReferenceElement> references, UUID sourceId, String typeName) {
        if (sourceId == null || typeName == null || typeName.isBlank()) {
            return null;
        }
        if (references.containsKey(sourceId)) {
            return sourceId;
        }
        ModelResource.ClassifierResource classifier = classifiersById.get(sourceId);
        if (classifier == null) {
            List<ModelResource.ClassifierResource> byName = model.classifiers().stream()
                    .filter(candidate -> typeName.equals(candidate.name())).toList();
            if (byName.size() == 1) {
                classifier = byName.get(0);
            }
        }
        references.put(sourceId, new TypeReferenceElement(sourceId, classifier == null ? null : classifier.id(),
                classifier == null ? typeName : null));
        return sourceId;
    }

    private static MultiplicityKind multiplicity(MultiplicityKind value) {
        return value == null ? MultiplicityKind.EXACTLY_ONE : value;
    }

    /** Bounds persist only for RANGE, and a RANGE without a lower bound reads as 0..*, as the handler stores it. */
}
