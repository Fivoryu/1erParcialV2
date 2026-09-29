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
 * Row of {@code diagrammer.uml_constraint}.
 *
 * <p>{@code ownerType} / {@code ownerId} are a polymorphic pair. This task maps them as plain
 * columns; the polymorphic owner rule is enforced by application code in a later task.
 */
@Entity
@Table(name = "uml_constraint", schema = "diagrammer")
public class UmlConstraint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "constraint_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "owner_type")
    private TargetType ownerType;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "expression", nullable = false, columnDefinition = "text")
    private String expression;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "severity", nullable = false)
    private SeverityKind severity;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected UmlConstraint() {
    }

    public static UmlConstraint create(UmlModel model, TargetType ownerType, UUID ownerId, String expression,
            SeverityKind severity) {
        return create(null, model, ownerType, ownerId, expression, severity);
    }

    public static UmlConstraint create(UUID id, UmlModel model, TargetType ownerType, UUID ownerId, String expression,
            SeverityKind severity) {
        UmlConstraint constraint = new UmlConstraint();
        constraint.id = id;
        constraint.model = model;
        constraint.ownerType = ownerType;
        constraint.ownerId = ownerId;
        constraint.expression = expression;
        constraint.severity = severity;
        constraint.createdAt = OffsetDateTime.now();
        return constraint;
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public TargetType getOwnerType() {
        return ownerType;
    }

    public void setOwnerType(TargetType ownerType) {
        this.ownerType = ownerType;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public SeverityKind getSeverity() {
        return severity;
    }

    public void setSeverity(SeverityKind severity) {
        this.severity = severity;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
