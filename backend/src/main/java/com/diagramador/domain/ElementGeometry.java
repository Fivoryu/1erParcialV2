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
 * Row of {@code diagrammer.element_geometry}.
 *
 * <p>{@code targetType} / {@code targetId} are a polymorphic pair with no foreign key; the
 * polymorphic integrity rule is enforced by application code in a later task.
 */
@Entity
@Table(name = "element_geometry", schema = "diagrammer")
public class ElementGeometry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "geometry_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "view_id", nullable = false)
    private DiagramView view;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "target_type", nullable = false)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "x", nullable = false)
    private int x;

    @Column(name = "y", nullable = false)
    private int y;

    @Column(name = "w", nullable = false)
    private int w;

    @Column(name = "h", nullable = false)
    private int h;

    @Column(name = "z_order", nullable = false)
    private int zOrder;

    protected ElementGeometry() {
    }

    public static ElementGeometry create(DiagramView view, TargetType targetType, UUID targetId, int x, int y, int w,
            int h, int zOrder) {
        ElementGeometry geometry = new ElementGeometry();
        geometry.view = view;
        geometry.targetType = targetType;
        geometry.targetId = targetId;
        geometry.x = x;
        geometry.y = y;
        geometry.w = w;
        geometry.h = h;
        geometry.zOrder = zOrder;
        return geometry;
    }

    public UUID getId() {
        return id;
    }

    public DiagramView getView() {
        return view;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(TargetType targetType) {
        this.targetType = targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public void setTargetId(UUID targetId) {
        this.targetId = targetId;
    }

    public int getX() {
        return x;
    }

    public void setX(int x) {
        this.x = x;
    }

    public int getY() {
        return y;
    }

    public void setY(int y) {
        this.y = y;
    }

    public int getW() {
        return w;
    }

    public void setW(int w) {
        this.w = w;
    }

    public int getH() {
        return h;
    }

    public void setH(int h) {
        this.h = h;
    }

    public int getZOrder() {
        return zOrder;
    }

    public void setZOrder(int zOrder) {
        this.zOrder = zOrder;
    }
}
