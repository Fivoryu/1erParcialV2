package com.diagramador.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Row of {@code diagrammer.uml_model}.
 */
@Entity
@Table(name = "uml_model", schema = "diagrammer")
public class UmlModel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "model_id", nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, unique = true)
    private Project project;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "current_revision", nullable = false)
    private long currentRevision;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected UmlModel() {
    }

    public static UmlModel create(Project project, String name) {
        UmlModel model = new UmlModel();
        model.project = project;
        model.name = name;
        model.currentRevision = 0L;
        model.createdAt = OffsetDateTime.now();
        return model;
    }

    public UUID getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getCurrentRevision() {
        return currentRevision;
    }

    public void setCurrentRevision(long currentRevision) {
        this.currentRevision = currentRevision;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
