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
 * Row of {@code diagrammer.classifier}.
 */
@Entity
@Table(name = "classifier", schema = "diagrammer")
public class Classifier {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "classifier_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    /** Optional container package of this classifier. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "namespace_id")
    private Classifier namespace;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "kind", nullable = false)
    private ClassifierKind kind;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "is_abstract", nullable = false)
    private boolean isAbstract;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Classifier() {
    }

    public static Classifier create(UmlModel model, Classifier namespace, ClassifierKind kind, String name,
            boolean isAbstract) {
        Classifier classifier = new Classifier();
        classifier.model = model;
        classifier.namespace = namespace;
        classifier.kind = kind;
        classifier.name = name;
        classifier.isAbstract = isAbstract;
        classifier.createdAt = OffsetDateTime.now();
        return classifier;
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public Classifier getNamespace() {
        return namespace;
    }

    public void setNamespace(Classifier namespace) {
        this.namespace = namespace;
    }

    public ClassifierKind getKind() {
        return kind;
    }

    public void setKind(ClassifierKind kind) {
        this.kind = kind;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    public void setAbstract(boolean isAbstract) {
        this.isAbstract = isAbstract;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
