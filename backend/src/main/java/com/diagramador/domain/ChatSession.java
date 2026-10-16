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

/**
 * Chat session row of {@code diagrammer.chat_session}. A session is one conversation inside a
 * {@link DiagramView} (the per-diagram frontier fixed by PD-02/L33); it never holds its own
 * LLM instance and never copies the history of another session.
 */
@Entity
@Table(name = "chat_session", schema = "diagrammer")
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID id;

    // EAGER on purpose: DiagramMessage.create derives the model from the view, and unproxied
    // callers (no open Hibernate session) must be able to read it from a detached session.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "view_id", nullable = false)
    private DiagramView view;

    @Column(name = "title", nullable = false)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private UserAccount createdBy;

    /** Marks the compatibility session the legacy project-level chat endpoints serve. */
    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ChatSession() {
    }

    public static ChatSession create(DiagramView view, UserAccount createdBy, String title, boolean isDefault) {
        ChatSession session = new ChatSession();
        session.view = view;
        session.createdBy = createdBy;
        session.title = title;
        session.isDefault = isDefault;
        session.createdAt = OffsetDateTime.now();
        return session;
    }

    public UUID getId() {
        return id;
    }

    public DiagramView getView() {
        return view;
    }

    public String getTitle() {
        return title;
    }

    public UserAccount getCreatedBy() {
        return createdBy;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
