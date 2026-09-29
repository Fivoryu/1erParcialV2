package com.diagramador.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.TargetType;
import java.util.List;
import java.util.UUID;

public record ModelResource(
        UUID id,
        UUID projectId,
        long revision,
        List<ViewResource> views,
        List<ClassifierResource> classifiers,
        List<RelationshipResource> relationships) {

    public ModelResource {
        views = views == null ? List.of() : List.copyOf(views);
        classifiers = classifiers == null ? List.of() : List.copyOf(classifiers);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
    }

    public record ViewResource(
            UUID id,
            String name,
            @JsonProperty("is_default") boolean isDefault,
            @JsonProperty("element_geometry") List<GeometryResource> elementGeometry,
            @JsonProperty("connector_path") List<ConnectorPathResource> connectorPath) {

        public ViewResource {
            elementGeometry = elementGeometry == null ? List.of() : List.copyOf(elementGeometry);
            connectorPath = connectorPath == null ? List.of() : List.copyOf(connectorPath);
        }
    }

    public record GeometryResource(
            UUID id,
            TargetType targetType,
            UUID targetId,
            int x,
            int y,
            int w,
            int h,
            int zOrder) {
    }

    public record ConnectorPathResource(UUID id, UUID relationshipId, int ordinal, int x, int y) {
    }

    public record ClassifierResource(
            UUID id,
            String name,
            ClassifierKind kind,
            @JsonProperty("isAbstract") boolean isAbstract,
            UUID namespaceId,
            List<AttributeResource> attributes,
            List<OperationResource> operations,
            List<ConstraintResource> constraints) {

        public ClassifierResource {
            attributes = attributes == null ? List.of() : List.copyOf(attributes);
            operations = operations == null ? List.of() : List.copyOf(operations);
            constraints = constraints == null ? List.of() : List.copyOf(constraints);
        }
    }

    public record AttributeResource(
            UUID id,
            String name,
            String kind,
            String visibility,
            MultiplicityKind multiplicity,
            String defaultValue,
            UUID typeReferenceId,
            String type,
            int ordinal) {
    }

    public record OperationResource(
            UUID id,
            String name,
            String visibility,
            @JsonProperty("isAbstract") boolean isAbstract,
            @JsonProperty("isStatic") boolean isStatic,
            UUID returnTypeReferenceId,
            String returnType,
            int ordinal,
            List<ParameterResource> parameters) {

        public OperationResource {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }
    }

    public record ParameterResource(
            UUID id,
            String name,
            String direction,
            UUID typeReferenceId,
            String type,
            int ordinal) {
    }

    public record ConstraintResource(UUID id, String expression, String severity) {
    }

    public record RelationshipResource(
            UUID id,
            String kind,
            String name,
            List<RelationshipEndResource> ends) {

        public RelationshipResource {
            ends = ends == null ? List.of() : List.copyOf(ends);
        }
    }

    public record RelationshipEndResource(
            UUID id,
            UUID classifierId,
            String role,
            MultiplicityKind multiplicity,
            NavigationKind navigability,
            String aggregationKind,
            short ordinal) {
    }
}
