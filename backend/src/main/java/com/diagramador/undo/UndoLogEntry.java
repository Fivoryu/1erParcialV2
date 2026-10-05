package com.diagramador.undo;

import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.domain.ModelCommand;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Row of {@code diagrammer.undo_log}: the pre-value snapshot of one accepted command (IA4-04). */
@Entity
@Table(name = "undo_log", schema = "diagrammer")
public class UndoLogEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    // EAGER: the redo path reads the canonical envelope of the original command after detach.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "command_id", nullable = false)
    private ModelCommand command;

    @Column(name = "type", nullable = false, length = 128)
    private String type;

    // The pre-value state of the envelope targets, captured before the command was applied.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pre_snapshot", columnDefinition = "jsonb")
    private JsonNode preSnapshot;

    @Column(name = "undone_at")
    private OffsetDateTime undoneAt;

    @Column(name = "redone_at")
    private OffsetDateTime redoneAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected UndoLogEntry() {
    }

    private UndoLogEntry(UUID id, UmlModel model, UserAccount user, ModelCommand command, String type,
            JsonNode preSnapshot) {
        this.id = id;
        this.model = model;
        this.user = user;
        this.command = command;
        this.type = type;
        this.preSnapshot = preSnapshot;
    }

    public static UndoLogEntry create(UmlModel model, UserAccount user, ModelCommand command, String type,
            JsonNode preSnapshot) {
        if (model == null || command == null) {
            throw new IllegalArgumentException("undo_log requires a model and a command");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("undo_log requires a command type");
        }
        return new UndoLogEntry(UUID.randomUUID(), model, user, command, type, preSnapshot);
    }

    /** The undo happened: the entry becomes the redo candidate and stops being an undo candidate. */
    public void markUndone(OffsetDateTime at) {
        this.undoneAt = at;
        this.redoneAt = null;
    }

    /** The redo happened: the entry is live again, so it can be undone once more. */
    public void markRedone(OffsetDateTime at) {
        this.undoneAt = null;
        this.redoneAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public UserAccount getUser() {
        return user;
    }

    public ModelCommand getCommand() {
        return command;
    }

    public String getType() {
        return type;
    }

    public JsonNode getPreSnapshot() {
        return preSnapshot;
    }

    public OffsetDateTime getUndoneAt() {
        return undoneAt;
    }

    public OffsetDateTime getRedoneAt() {
        return redoneAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
