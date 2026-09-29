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

/** Row of {@code diagrammer.tombstone}. */
@Entity
@Table(name = "tombstone", schema = "diagrammer")
public class Tombstone {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "tombstone_id", nullable = false, updatable = false)
    private UUID tombstoneId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "target_type", nullable = false)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cascade_id")
    private CascadeGroup cascade;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "command_id")
    private ModelCommand command;

    @Column(name = "deleted_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime deletedAt;

    protected Tombstone() {
    }

    public static Tombstone create(UmlModel model, TargetType targetType, UUID targetId, CascadeGroup cascade,
            ModelCommand command) {
        Tombstone tombstone = new Tombstone();
        tombstone.model = model;
        tombstone.targetType = targetType;
        tombstone.targetId = targetId;
        tombstone.cascade = cascade;
        tombstone.command = command;
        return tombstone;
    }

    public UUID getTombstoneId() {
        return tombstoneId;
    }

    public UmlModel getModel() {
        return model;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public CascadeGroup getCascade() {
        return cascade;
    }

    public ModelCommand getCommand() {
        return command;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }
}
