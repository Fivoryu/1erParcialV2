package com.diagramador.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.diagramador.domain.PersistenceStore;
import com.diagramador.domain.TargetType;
import java.util.List;
import java.util.UUID;

public record ModelResource(
        UUID id,
        UUID projectId,
        long revision,
        UUID versionId,
        List<ViewResource> views,
        List<ClassifierResource> classifiers,
        List<RelationshipResource> relationships,
        @JsonProperty("view_style") List<ViewStyleResource> viewStyle,
        List<NoteResource> notes) {

    /** Legacy shape used by the XMI emitter/reader path: styles stay out of the exchange. */
    public ModelResource(UUID id, UUID projectId, long revision, UUID versionId, List<ViewResource> views,
            List<ClassifierResource> classifiers, List<RelationshipResource> relationships) {
        this(id, projectId, revision, versionId, views, classifiers, relationships, List.of(), List.of());
    }

    /** Pre-notes shape: no notes, as old snapshots without the field read. */
    public ModelResource(UUID id, UUID projectId, long revision, UUID versionId, List<ViewResource> views,
            List<ClassifierResource> classifiers, List<RelationshipResource> relationships,
            List<ViewStyleResource> viewStyle) {
        this(id, projectId, revision, versionId, views, classifiers, relationships, viewStyle, List.of());
    }

    public ModelResource {
        views = views == null ? List.of() : List.copyOf(views);
        classifiers = classifiers == null ? List.of() : List.copyOf(classifiers);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
        viewStyle = viewStyle == null ? List.of() : List.copyOf(viewStyle);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** UML Comment: free text annotating zero or more classifiers or relationships of the model. */
    public record NoteResource(UUID id, String body, List<NoteLinkResource> annotatedElements) {

        public NoteResource {
            annotatedElements = annotatedElements == null ? List.of() : List.copyOf(annotatedElements);
        }
    }

    /** One annotated element of a note; {@code targetType} is CLASSIFIER or RELATIONSHIP. */
    public record NoteLinkResource(TargetType targetType, UUID targetId) {
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

    /** IA4-07: per-view appearance of a relationship, read by the canvas but never exported to XMI. */
    public record ViewStyleResource(
            UUID viewId,
            UUID targetId,
            String routing,
            int cornerRadius,
            int strokeWidth,
            boolean dashed,
            RouteAdjustmentResource routeAdjustment) {
    }

    /** ROR-02: frame-relative orthogonal route adjustment, null when the row has none. */
    public record RouteAdjustmentResource(String axis, List<Integer> corridors, List<Integer> levels,
            RouteAnchorsResource anchors) {

        public RouteAdjustmentResource {
            corridors = corridors == null ? List.of() : List.copyOf(corridors);
            levels = levels == null ? List.of() : List.copyOf(levels);
        }
    }

    /** ANC-01: the per-end anchors of the orthogonal route, null when the row has none. */
    public record RouteAnchorsResource(RouteAnchorResource source, RouteAnchorResource target) {
    }

    /** ANC-02: one anchor end; the signed px offset is measured from the centre of the side. */
    public record RouteAnchorResource(String side, int offset) {
    }

    public record ClassifierResource(
            UUID id,
            String name,
            ClassifierKind kind,
            @JsonProperty("isAbstract") boolean isAbstract,
            UUID namespaceId,
            List<AttributeResource> attributes,
            List<OperationResource> operations,
            List<ConstraintResource> constraints,
            @JsonInclude(JsonInclude.Include.NON_NULL) PersistenceStore storeOverride,
            @JsonInclude(JsonInclude.Include.NON_NULL) PersistenceStore store,
            @JsonInclude(JsonInclude.Include.NON_NULL) String storeReason,
            String stereotype) {

        public ClassifierResource {
            attributes = attributes == null ? List.of() : List.copyOf(attributes);
            operations = operations == null ? List.of() : List.copyOf(operations);
            constraints = constraints == null ? List.of() : List.copyOf(constraints);
        }

        /** A classifier without a store decision: the override is unset and the effective store is not computed. */
        public ClassifierResource(UUID id, String name, ClassifierKind kind, boolean isAbstract, UUID namespaceId,
                List<AttributeResource> attributes, List<OperationResource> operations,
                List<ConstraintResource> constraints) {
            this(id, name, kind, isAbstract, namespaceId, attributes, operations, constraints, null, null, null, null);
        }

        /** Pre-stereotype shape: no stereotype (null), as old snapshots without the field read. */
        public ClassifierResource(UUID id, String name, ClassifierKind kind, boolean isAbstract, UUID namespaceId,
                List<AttributeResource> attributes, List<OperationResource> operations,
                List<ConstraintResource> constraints, PersistenceStore storeOverride, PersistenceStore store,
                String storeReason) {
            this(id, name, kind, isAbstract, namespaceId, attributes, operations, constraints, storeOverride, store,
                    storeReason, null);
        }

        /** The same classifier carrying the effective store and the reason the classifier gave for it. */
        public ClassifierResource withStore(PersistenceStore effectiveStore, String reason) {
            return new ClassifierResource(id, name, kind, isAbstract, namespaceId, attributes, operations,
                    constraints, storeOverride, effectiveStore, reason, stereotype);
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
            int ordinal,
            Integer lowerBound,
            Integer upperBound,
            String stereotype,
            @JsonProperty("isStatic") boolean isStatic,
            @JsonProperty("isDerived") boolean isDerived) {
        /** Pre-RANGE shape: bounds absent (null), as old snapshots without the fields read. */
        public AttributeResource(UUID id, String name, String kind, String visibility, MultiplicityKind multiplicity,
                String defaultValue, UUID typeReferenceId, String type, int ordinal) {
            this(id, name, kind, visibility, multiplicity, defaultValue, typeReferenceId, type, ordinal, null, null,
                    null);
        }

        /** Pre-static/derived shape: both flags false, as old snapshots without the fields read. */
        public AttributeResource(UUID id, String name, String kind, String visibility, MultiplicityKind multiplicity,
                String defaultValue, UUID typeReferenceId, String type, int ordinal, Integer lowerBound,
                Integer upperBound, String stereotype) {
            this(id, name, kind, visibility, multiplicity, defaultValue, typeReferenceId, type, ordinal, lowerBound,
                    upperBound, stereotype, false, false);
        }

        /** Pre-stereotype shape: no stereotype (null), as old snapshots without the field read. */
        public AttributeResource(UUID id, String name, String kind, String visibility, MultiplicityKind multiplicity,
                String defaultValue, UUID typeReferenceId, String type, int ordinal, Integer lowerBound,
                Integer upperBound) {
            this(id, name, kind, visibility, multiplicity, defaultValue, typeReferenceId, type, ordinal, lowerBound,
                    upperBound, null);
        }
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
            List<ParameterResource> parameters,
            String stereotype) {

        public OperationResource {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }

        /** Pre-stereotype shape: no stereotype (null), as old snapshots without the field read. */
        public OperationResource(UUID id, String name, String visibility, boolean isAbstract, boolean isStatic,
                UUID returnTypeReferenceId, String returnType, int ordinal, List<ParameterResource> parameters) {
            this(id, name, visibility, isAbstract, isStatic, returnTypeReferenceId, returnType, ordinal, parameters,
                    null);
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
            @JsonInclude(JsonInclude.Include.NON_NULL) String stereotype,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID associationClassId,
            List<RelationshipEndResource> ends,
            List<ConstraintResource> constraints) {

        public RelationshipResource {
            ends = ends == null ? List.of() : List.copyOf(ends);
            constraints = constraints == null ? List.of() : List.copyOf(constraints);
        }

        /** A relationship without an association class, which is every one that has no class of its own. */
        public RelationshipResource(UUID id, String kind, String name, String stereotype,
                List<RelationshipEndResource> ends, List<ConstraintResource> constraints) {
            this(id, kind, name, stereotype, null, ends, constraints);
        }

        /** A relationship without a stereotype, which is every one that is not a stereotyped dependency. */
        public RelationshipResource(UUID id, String kind, String name, List<RelationshipEndResource> ends,
                List<ConstraintResource> constraints) {
            this(id, kind, name, null, null, ends, constraints);
        }
    }

    public record RelationshipEndResource(
            UUID id,
            UUID classifierId,
            String role,
            MultiplicityKind multiplicity,
            NavigationKind navigability,
            String aggregationKind,
            short ordinal,
            Integer lowerBound,
            Integer upperBound,
            @JsonProperty("isOrdered") boolean isOrdered,
            @JsonProperty("isUnique") boolean isUnique) {
        /** Pre-RANGE shape: bounds absent (null), as old snapshots without the fields read. */
        public RelationshipEndResource(UUID id, UUID classifierId, String role, MultiplicityKind multiplicity,
                NavigationKind navigability, String aggregationKind, short ordinal) {
            this(id, classifierId, role, multiplicity, navigability, aggregationKind, ordinal, null, null, false,
                    false);
        }

        /** Pre-ordered/unique shape: both flags false, as old snapshots without the fields read. */
        public RelationshipEndResource(UUID id, UUID classifierId, String role, MultiplicityKind multiplicity,
                NavigationKind navigability, String aggregationKind, short ordinal, Integer lowerBound,
                Integer upperBound) {
            this(id, classifierId, role, multiplicity, navigability, aggregationKind, ordinal, lowerBound, upperBound,
                    false, false);
        }
    }
}
