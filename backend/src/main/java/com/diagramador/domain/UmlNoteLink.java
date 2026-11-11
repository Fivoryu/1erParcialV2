package com.diagramador.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Row of {@code diagrammer.uml_note_link}: one annotated element of a note. {@code targetType} is CLASSIFIER or
 * RELATIONSHIP; {@code targetId} is polymorphic with no foreign key, so deleting the element removes its links in
 * application code.
 */
@Entity
@Table(name = "uml_note_link", schema = "diagrammer")
public class UmlNoteLink {

    @EmbeddedId
    private Key key;

    protected UmlNoteLink() {
    }

    public static UmlNoteLink create(UUID noteId, TargetType targetType, UUID targetId) {
        UmlNoteLink link = new UmlNoteLink();
        link.key = new Key(noteId, targetType.name(), targetId);
        return link;
    }

    public UUID getNoteId() {
        return key.noteId;
    }

    public TargetType getTargetType() {
        return TargetType.valueOf(key.targetType);
    }

    public UUID getTargetId() {
        return key.targetId;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "note_id", nullable = false)
        private UUID noteId;

        @Column(name = "target_type", nullable = false, length = 16)
        private String targetType;

        @Column(name = "target_id", nullable = false)
        private UUID targetId;

        protected Key() {
        }

        Key(UUID noteId, String targetType, UUID targetId) {
            this.noteId = noteId;
            this.targetType = targetType;
            this.targetId = targetId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(noteId, key.noteId)
                    && Objects.equals(targetType, key.targetType) && Objects.equals(targetId, key.targetId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(noteId, targetType, targetId);
        }
    }
}
