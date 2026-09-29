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
 * Row of {@code diagrammer.uml_attribute}.
 */
@Entity
@Table(name = "uml_attribute", schema = "diagrammer")
public class UmlAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "attribute_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "classifier_id", nullable = false)
    private Classifier classifier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "type_reference_id")
    private TypeReference typeReference;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "visibility", nullable = false, length = 16)
    private String visibility;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "multiplicity", nullable = false)
    private MultiplicityKind multiplicity;

    @Column(name = "default_value", columnDefinition = "text")
    private String defaultValue;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected UmlAttribute() {
    }

    public static UmlAttribute create(Classifier classifier, TypeReference typeReference, String name, String kind,
            String visibility, MultiplicityKind multiplicity, String defaultValue, int ordinal) {
        return create(null, classifier, typeReference, name, kind, visibility, multiplicity, defaultValue, ordinal);
    }

    public static UmlAttribute create(UUID id, Classifier classifier, TypeReference typeReference, String name,
            String kind, String visibility, MultiplicityKind multiplicity, String defaultValue, int ordinal) {
        UmlAttribute attribute = new UmlAttribute();
        attribute.id = id;
        attribute.classifier = classifier;
        attribute.typeReference = typeReference;
        attribute.name = name;
        attribute.kind = kind;
        attribute.visibility = visibility;
        attribute.multiplicity = multiplicity;
        attribute.defaultValue = defaultValue;
        attribute.ordinal = ordinal;
        attribute.createdAt = OffsetDateTime.now();
        return attribute;
    }

    public UUID getId() {
        return id;
    }

    public Classifier getClassifier() {
        return classifier;
    }

    public TypeReference getTypeReference() {
        return typeReference;
    }

    public void setTypeReference(TypeReference typeReference) {
        this.typeReference = typeReference;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public MultiplicityKind getMultiplicity() {
        return multiplicity;
    }

    public void setMultiplicity(MultiplicityKind multiplicity) {
        this.multiplicity = multiplicity;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
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
