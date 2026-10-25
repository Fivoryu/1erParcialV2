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
 * Row of {@code diagrammer.diagram_view}.
 */
@Entity
@Table(name = "diagram_view", schema = "diagrammer")
public class DiagramView {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "view_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "view_kind", nullable = false, length = 32)
    private String viewKind;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected DiagramView() {
    }

    public static DiagramView create(UmlModel model, String name, String viewKind, boolean isDefault) {
        return create(null, model, name, viewKind, isDefault);
    }

    /** Identity-preserving factory used by snapshot materialization. */
    public static DiagramView create(UUID id, UmlModel model, String name, String viewKind, boolean isDefault) {
        DiagramView view = new DiagramView();
        view.id = id;
        view.model = model;
        view.name = name;
        view.viewKind = viewKind;
        view.isDefault = isDefault;
        view.createdAt = OffsetDateTime.now();
        return view;
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getViewKind() {
        return viewKind;
    }

    public void setViewKind(String viewKind) {
        this.viewKind = viewKind;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
