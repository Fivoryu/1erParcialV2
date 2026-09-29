package com.diagramador.domain;

import com.diagramador.command.ValidationState;
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

/** Row of {@code diagrammer.model_version}. */
@Entity
@Table(name = "model_version", schema = "diagrammer")
public class ModelVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "parent_revision")
    private Long parentRevision;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "state_hash", nullable = false, columnDefinition = "char(64)")
    private String stateHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "validation_state", nullable = false)
    private ValidationState validationState;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private UserAccount createdByUser;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ModelVersion() {
    }

    public static ModelVersion create(UmlModel model, long revision, Long parentRevision, String stateHash,
            ValidationState validationState, UserAccount createdByUser) {
        ModelVersion version = new ModelVersion();
        version.model = model;
        version.revision = revision;
        version.parentRevision = parentRevision;
        version.stateHash = stateHash;
        version.validationState = validationState;
        version.createdByUser = createdByUser;
        return version;
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public long getRevision() {
        return revision;
    }

    public Long getParentRevision() {
        return parentRevision;
    }

    public String getStateHash() {
        return stateHash;
    }

    public ValidationState getValidationState() {
        return validationState;
    }

    public UserAccount getCreatedByUser() {
        return createdByUser;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
