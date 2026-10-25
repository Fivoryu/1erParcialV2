package com.diagramador.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Row of {@code diagrammer.type_reference}: either a classifier reference or a primitive name.
 */
@Entity
@Table(name = "type_reference", schema = "diagrammer")
public class TypeReference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "type_reference_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "classifier_id")
    private Classifier classifier;

    @Column(name = "primitive_name", length = 64)
    private String primitiveName;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected TypeReference() {
    }

    public static TypeReference forClassifier(UmlModel model, Classifier classifier) {
        TypeReference reference = new TypeReference();
        reference.model = model;
        reference.classifier = classifier;
        reference.createdAt = OffsetDateTime.now();
        return reference;
    }

    /** Identity-preserving factory used by snapshot materialization. */
    public static TypeReference forClassifier(UUID id, UmlModel model, Classifier classifier) {
        TypeReference reference = new TypeReference();
        reference.id = id;
        reference.model = model;
        reference.classifier = classifier;
        reference.createdAt = OffsetDateTime.now();
        return reference;
    }

    /** Identity-preserving factory used by snapshot materialization. */
    public static TypeReference forPrimitive(UUID id, UmlModel model, String primitiveName) {
        TypeReference reference = new TypeReference();
        reference.id = id;
        reference.model = model;
        reference.primitiveName = primitiveName;
        reference.createdAt = OffsetDateTime.now();
        return reference;
    }

    public static TypeReference forPrimitive(UmlModel model, String primitiveName) {
        TypeReference reference = new TypeReference();
        reference.model = model;
        reference.primitiveName = primitiveName;
        reference.createdAt = OffsetDateTime.now();
        return reference;
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public Classifier getClassifier() {
        return classifier;
    }

    public void setClassifier(Classifier classifier) {
        this.classifier = classifier;
    }

    public String getPrimitiveName() {
        return primitiveName;
    }

    public void setPrimitiveName(String primitiveName) {
        this.primitiveName = primitiveName;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
