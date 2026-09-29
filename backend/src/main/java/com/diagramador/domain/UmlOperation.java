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
 * Row of {@code diagrammer.uml_operation}.
 */
@Entity
@Table(name = "uml_operation", schema = "diagrammer")
public class UmlOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "operation_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "classifier_id", nullable = false)
    private Classifier classifier;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_type_reference_id")
    private TypeReference returnTypeReference;

    @Column(name = "visibility", nullable = false, length = 16)
    private String visibility;

    @Column(name = "is_abstract", nullable = false)
    private boolean isAbstract;

    @Column(name = "is_static", nullable = false)
    private boolean isStatic;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected UmlOperation() {
    }

    public static UmlOperation create(Classifier classifier, String name, TypeReference returnTypeReference,
            String visibility, boolean isAbstract, boolean isStatic, int ordinal) {
        return create(null, classifier, name, returnTypeReference, visibility, isAbstract, isStatic, ordinal);
    }

    public static UmlOperation create(UUID id, Classifier classifier, String name, TypeReference returnTypeReference,
            String visibility, boolean isAbstract, boolean isStatic, int ordinal) {
        UmlOperation operation = new UmlOperation();
        operation.id = id;
        operation.classifier = classifier;
        operation.name = name;
        operation.returnTypeReference = returnTypeReference;
        operation.visibility = visibility;
        operation.isAbstract = isAbstract;
        operation.isStatic = isStatic;
        operation.ordinal = ordinal;
        operation.createdAt = OffsetDateTime.now();
        return operation;
    }

    public UUID getId() {
        return id;
    }

    public Classifier getClassifier() {
        return classifier;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TypeReference getReturnTypeReference() {
        return returnTypeReference;
    }

    public void setReturnTypeReference(TypeReference returnTypeReference) {
        this.returnTypeReference = returnTypeReference;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    public void setAbstract(boolean isAbstract) {
        this.isAbstract = isAbstract;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public void setStatic(boolean isStatic) {
        this.isStatic = isStatic;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(int ordinal) {
        this.ordinal = ordinal;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
