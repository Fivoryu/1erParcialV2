package com.diagramador.domain;

import com.diagramador.command.AuthorizationClassification;
import com.diagramador.command.CommandOrigin;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Row of {@code diagrammer.model_command}. */
@Entity
@Table(name = "model_command", schema = "diagrammer")
public class ModelCommand {

    @Id
    @Column(name = "command_id", nullable = false, updatable = false)
    // The author-generated operationId is opaque and stable, so it is persisted as command_id.
    private UUID commandId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_user_id")
    private UserAccount author;

    @Column(name = "base_revision", nullable = false)
    private long baseRevision;

    @Column(name = "result_revision")
    private Long resultRevision;

    @Column(name = "operation", nullable = false, length = 128)
    private String operation;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "target_type")
    private TargetType targetType;

    @Column(name = "target_id")
    private UUID targetId;

    // The JSONB value is the complete canonical envelope, not only its command payload.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private JsonNode canonicalEnvelope;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "origin", nullable = false)
    private CommandOrigin origin;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "authorization_classification", nullable = false)
    private AuthorizationClassification authorizationClassification;

    @Column(name = "causal_parent_operation_id")
    private UUID causalParentOperationId;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ModelCommand() {
    }

    public static ModelCommand create(UUID commandId, UmlModel model, UserAccount author, long baseRevision,
            Long resultRevision, String operation, TargetType targetType, UUID targetId, JsonNode canonicalEnvelope,
            CommandOrigin origin, AuthorizationClassification authorizationClassification,
            UUID causalParentOperationId, String idempotencyKey) {
        ModelCommand command = new ModelCommand();
        command.commandId = commandId;
        command.model = model;
        command.author = author;
        command.baseRevision = baseRevision;
        command.resultRevision = resultRevision;
        command.operation = operation;
        command.targetType = targetType;
        command.targetId = targetId;
        command.canonicalEnvelope = canonicalEnvelope;
        command.origin = origin;
        command.authorizationClassification = authorizationClassification;
        command.causalParentOperationId = causalParentOperationId;
        command.idempotencyKey = idempotencyKey;
        return command;
    }

    public UUID getCommandId() {
        return commandId;
    }

    public UmlModel getModel() {
        return model;
    }

    public UserAccount getAuthor() {
        return author;
    }

    public long getBaseRevision() {
        return baseRevision;
    }

    public Long getResultRevision() {
        return resultRevision;
    }

    public String getOperation() {
        return operation;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public JsonNode getCanonicalEnvelope() {
        return canonicalEnvelope;
    }

    public CommandOrigin getOrigin() {
        return origin;
    }

    public AuthorizationClassification getAuthorizationClassification() {
        return authorizationClassification;
    }

    public UUID getCausalParentOperationId() {
        return causalParentOperationId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
