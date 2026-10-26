package com.diagramador.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Row of {@code diagrammer.uml_relationship}.
 */
@Entity
@Table(name = "uml_relationship", schema = "diagrammer")
public class UmlRelationship {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "relationship_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "kind", nullable = false)
    private RelationshipKind kind;

    @Column(name = "name", length = 255)
    private String name;

    /** The class that is also this association (UML association class); NULL means none. */
    @Column(name = "association_class_id")
    private UUID associationClassId;

    /** A DependencyStereotype name; only a DEPENDENCY has one, and NULL means none. */
    @Column(name = "stereotype", length = 32)
    private String stereotype;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected UmlRelationship() {
    }

    public static UmlRelationship create(UmlModel model, RelationshipKind kind, String name) {
        return create(null, model, kind, name);
    }

    /** Identity-preserving factory used by snapshot materialization. */
    public static UmlRelationship create(UUID id, UmlModel model, RelationshipKind kind, String name) {
        UmlRelationship relationship = new UmlRelationship();
        relationship.id = id;
        relationship.model = model;
        relationship.kind = kind;
        relationship.name = name;
        relationship.createdAt = OffsetDateTime.now();
        return relationship;
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public RelationshipKind getKind() {
        return kind;
    }

    public void setKind(RelationshipKind kind) {
        this.kind = kind;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getAssociationClassId() {
        return associationClassId;
    }

    public void setAssociationClassId(UUID associationClassId) {
        this.associationClassId = associationClassId;
    }

    public String getStereotype() {
        return stereotype;
    }

    public void setStereotype(String stereotype) {
        this.stereotype = stereotype;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
