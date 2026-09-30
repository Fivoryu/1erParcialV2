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

/** Row of {@code diagrammer.generation_target}. */
@Entity
@Table(name = "generation_target", schema = "diagrammer")
public class GenerationTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @Column(name = "target_key", nullable = false, length = 128)
    private String targetKey;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "database_engine", nullable = false, length = 32)
    private String databaseEngine = "POSTGRESQL";

    @Column(name = "database_name", nullable = false, length = 200)
    private String databaseName;

    @Column(name = "database_schema", nullable = false, length = 200)
    private String databaseSchema = "public";

    @Column(name = "connection_config_ref", length = 512)
    private String connectionConfigRef;

    @Column(name = "configuration_revision", nullable = false)
    private int configurationRevision = 1;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected GenerationTarget() {
    }

    public static GenerationTarget create(UmlModel model, String targetKey, String name, String databaseEngine,
            String databaseName, String databaseSchema, String connectionConfigRef) {
        GenerationTarget target = new GenerationTarget();
        target.model = model;
        target.targetKey = targetKey;
        target.name = name;
        target.databaseEngine = databaseEngine;
        target.databaseName = databaseName;
        target.databaseSchema = databaseSchema;
        target.connectionConfigRef = connectionConfigRef;
        target.configurationRevision = 1;
        target.updatedAt = OffsetDateTime.now();
        return target;
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public void setModel(UmlModel model) {
        this.model = model;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public void setTargetKey(String targetKey) {
        this.targetKey = targetKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDatabaseEngine() {
        return databaseEngine;
    }

    public void setDatabaseEngine(String databaseEngine) {
        this.databaseEngine = databaseEngine;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getDatabaseSchema() {
        return databaseSchema;
    }

    public void setDatabaseSchema(String databaseSchema) {
        this.databaseSchema = databaseSchema;
    }

    public String getConnectionConfigRef() {
        return connectionConfigRef;
    }

    public void setConnectionConfigRef(String connectionConfigRef) {
        this.connectionConfigRef = connectionConfigRef;
    }

    public int getConfigurationRevision() {
        return configurationRevision;
    }

    public void setConfigurationRevision(int configurationRevision) {
        this.configurationRevision = configurationRevision;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
