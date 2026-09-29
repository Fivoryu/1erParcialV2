package com.diagramador.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Row of {@code diagrammer.cascade_group}. */
@Entity
@Table(name = "cascade_group", schema = "diagrammer")
public class CascadeGroup {

    @Id
    @Column(name = "cascade_id", nullable = false, updatable = false)
    private UUID cascadeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "root_command_id", nullable = false)
    private ModelCommand rootCommand;

    @Column(name = "element_count", nullable = false)
    private int elementCount;

    @Column(name = "restored_at")
    private OffsetDateTime restoredAt;

    protected CascadeGroup() {
    }

    public static CascadeGroup create(UmlModel model, ModelCommand rootCommand, int elementCount) {
        CascadeGroup group = new CascadeGroup();
        group.cascadeId = UUID.randomUUID();
        group.model = model;
        group.rootCommand = rootCommand;
        group.elementCount = elementCount;
        return group;
    }

    public UUID getCascadeId() {
        return cascadeId;
    }

    public UmlModel getModel() {
        return model;
    }

    public ModelCommand getRootCommand() {
        return rootCommand;
    }

    public int getElementCount() {
        return elementCount;
    }

    public OffsetDateTime getRestoredAt() {
        return restoredAt;
    }
}
