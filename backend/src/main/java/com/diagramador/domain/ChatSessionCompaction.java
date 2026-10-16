package com.diagramador.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The compacted summary of one chat session's live history (T6/L45): what the context builder
 * sends instead of re-reading every old message. The covered messages and their sources stay in
 * the database untouched (CA-25); deleting this row is safe and just de-compacts.
 */
@Entity
@Table(name = "chat_session_compaction", schema = "diagrammer")
public class ChatSessionCompaction {

    @Id
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private ChatSession session;

    @Column(name = "summary", nullable = false, columnDefinition = "text")
    private String summary;

    @Column(name = "up_to_message_id")
    private UUID upToMessageId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ChatSessionCompaction() {
    }

    public static ChatSessionCompaction create(ChatSession session, String summary, UUID upToMessageId) {
        ChatSessionCompaction compaction = new ChatSessionCompaction();
        compaction.session = session;
        compaction.summary = summary;
        compaction.upToMessageId = upToMessageId;
        return compaction;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public ChatSession getSession() {
        return session;
    }

    public String getSummary() {
        return summary;
    }

    public UUID getUpToMessageId() {
        return upToMessageId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public void setUpToMessageId(UUID upToMessageId) {
        this.upToMessageId = upToMessageId;
    }
}
