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

/** Row of {@code diagrammer.uml_note}: a UML Comment whose annotated elements are {@link UmlNoteLink} rows. */
@Entity
@Table(name = "uml_note", schema = "diagrammer")
public class UmlNote {

    /** Longest body a note may carry; mirrored by the {@code chk_note_body} check of V34. */
    public static final int MAX_BODY_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "note_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected UmlNote() {
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
