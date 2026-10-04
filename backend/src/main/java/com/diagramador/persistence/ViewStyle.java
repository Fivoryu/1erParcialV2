package com.diagramador.persistence;

import com.diagramador.domain.DiagramView;
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
 * Row of {@code diagrammer.view_style}: per-view appearance of a relationship
 * (routing mode, corner radius, stroke width, dash pattern).
 *
 * <p>{@code targetType} is frozen to {@code RELATIONSHIP} by the schema check in
 * {@code V12__view_style.sql} for this unit; {@code targetId} is polymorphic and
 * validated by the {@code style.set} handler.
 */
@Entity
@Table(name = "view_style", schema = "diagrammer")
public class ViewStyle {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "style_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "view_id", nullable = false)
    private DiagramView view;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType = "RELATIONSHIP";

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "routing", nullable = false, length = 16)
    private String routing = "STRAIGHT";

    @Column(name = "corner_radius", nullable = false)
    private int cornerRadius;

    @Column(name = "stroke_width", nullable = false)
    private int strokeWidth = 2;

    @Column(name = "dashed", nullable = false)
    private boolean dashed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ViewStyle() {
    }

    public static ViewStyle create(DiagramView view, UUID targetId, String routing, int cornerRadius,
            int strokeWidth, boolean dashed) {
        ViewStyle style = new ViewStyle();
        style.view = view;
        style.targetId = targetId;
        style.createdAt = OffsetDateTime.now();
        style.apply(routing, cornerRadius, strokeWidth, dashed);
        return style;
    }

    public void apply(String routing, int cornerRadius, int strokeWidth, boolean dashed) {
        this.routing = routing;
        this.cornerRadius = cornerRadius;
        this.strokeWidth = strokeWidth;
        this.dashed = dashed;
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public DiagramView getView() {
        return view;
    }

    public String getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public String getRouting() {
        return routing;
    }

    public int getCornerRadius() {
        return cornerRadius;
    }

    public int getStrokeWidth() {
        return strokeWidth;
    }

    public boolean isDashed() {
        return dashed;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
