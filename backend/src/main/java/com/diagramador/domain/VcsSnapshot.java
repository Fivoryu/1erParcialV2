package com.diagramador.domain;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Row of {@code diagrammer.vcs_snapshot}: the complete canonical diagram at one instant.
 *
 * <p>Snapshots are immutable and deduplicated per model by {@code state_hash}, so committing
 * the same state twice references the same row instead of storing the payload again. The
 * payload is the canonical JSON of {@code ModelSnapshotPayload}; materialization reads it
 * back into the live tables preserving every identity, which is what keeps a branch switch
 * from churning element ids.
 */
@Entity
@Table(name = "vcs_snapshot", schema = "diagrammer")
public class VcsSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "snapshot_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "state_hash", nullable = false, columnDefinition = "char(64)")
    private String stateHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private JsonNode payload;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected VcsSnapshot() {
    }

    public static VcsSnapshot create(UmlModel model, String stateHash, JsonNode payload) {
        VcsSnapshot snapshot = new VcsSnapshot();
        snapshot.model = model;
        snapshot.stateHash = stateHash;
        snapshot.payload = payload;
        return snapshot;
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public String getStateHash() {
        return stateHash;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
