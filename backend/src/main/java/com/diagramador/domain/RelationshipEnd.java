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
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Row of {@code diagrammer.relationship_end}.
 *
 * <p>The schema constrains {@code ordinal} to 1 or 2; the two ends of one relationship together
 * satisfy the "exactly two valid ends" invariant enforced by application code in later tasks.
 */
@Entity
@Table(name = "relationship_end", schema = "diagrammer")
public class RelationshipEnd {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "relationship_end_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "relationship_id", nullable = false)
    private UmlRelationship relationship;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "classifier_id", nullable = false)
    private Classifier classifier;

    @Column(name = "ordinal", nullable = false)
    private short ordinal;

    @Column(name = "role", length = 255)
    private String role;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "multiplicity", nullable = false)
    private MultiplicityKind multiplicity;

    @Column(name = "lower_bound")
    private Integer lowerBound;

    @Column(name = "upper_bound")
    private Integer upperBound;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "navigability", nullable = false)
    private NavigationKind navigability;

    @Column(name = "aggregation_kind", nullable = false, length = 16)
    private String aggregationKind;

    @Column(name = "is_ordered", nullable = false)
    private boolean isOrdered;

    @Column(name = "is_unique", nullable = false)
    private boolean isUnique;

    protected RelationshipEnd() {
    }

    public static RelationshipEnd create(UmlRelationship relationship, Classifier classifier, short ordinal, String role,
            MultiplicityKind multiplicity, NavigationKind navigability, String aggregationKind) {
        return create(null, relationship, classifier, ordinal, role, multiplicity, navigability, aggregationKind);
    }

    /** Identity-preserving factory used by snapshot materialization. */
    public static RelationshipEnd create(UUID id, UmlRelationship relationship, Classifier classifier, short ordinal,
            String role, MultiplicityKind multiplicity, NavigationKind navigability, String aggregationKind) {
        return create(id, relationship, classifier, ordinal, role, multiplicity, null, null, navigability,
                aggregationKind);
    }

    public static RelationshipEnd create(UUID id, UmlRelationship relationship, Classifier classifier, short ordinal,
            String role, MultiplicityKind multiplicity, Integer lowerBound, Integer upperBound,
            NavigationKind navigability, String aggregationKind) {
        RelationshipEnd end = new RelationshipEnd();
        end.id = id;
        end.relationship = relationship;
        end.classifier = classifier;
        end.ordinal = ordinal;
        end.role = role;
        end.multiplicity = multiplicity;
        end.lowerBound = lowerBound;
        end.upperBound = upperBound;
        end.navigability = navigability;
        end.aggregationKind = aggregationKind;
        return end;
    }

    public UUID getId() {
        return id;
    }

    public UmlRelationship getRelationship() {
        return relationship;
    }

    public Classifier getClassifier() {
        return classifier;
    }

    public void setClassifier(Classifier classifier) {
        this.classifier = classifier;
    }

    public short getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(short ordinal) {
        this.ordinal = ordinal;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public MultiplicityKind getMultiplicity() {
        return multiplicity;
    }

    public void setMultiplicity(MultiplicityKind multiplicity) {
        this.multiplicity = multiplicity;
    }

    public Integer getLowerBound() {
        return lowerBound;
    }

    public void setLowerBound(Integer lowerBound) {
        this.lowerBound = lowerBound;
    }

    public Integer getUpperBound() {
        return upperBound;
    }

    public void setUpperBound(Integer upperBound) {
        this.upperBound = upperBound;
    }

    public boolean isOrdered() {
        return isOrdered;
    }

    public void setOrdered(boolean isOrdered) {
        this.isOrdered = isOrdered;
    }

    public boolean isUnique() {
        return isUnique;
    }

    public void setUnique(boolean isUnique) {
        this.isUnique = isUnique;
    }

    public NavigationKind getNavigability() {
        return navigability;
    }

    public void setNavigability(NavigationKind navigability) {
        this.navigability = navigability;
    }

    public String getAggregationKind() {
        return aggregationKind;
    }

    public void setAggregationKind(String aggregationKind) {
        this.aggregationKind = aggregationKind;
    }
}
