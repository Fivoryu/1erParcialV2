package com.diagramador.mobile.chat;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "agent_message", schema = "diagrammer")
public class AgentMessage {
    @Id @Column(name = "message_id") UUID id;
    @Column(name = "conversation_id", nullable = false, updatable = false) UUID conversationId;
    @Column(nullable = false, updatable = false) long sequence;
    @Column(nullable = false, updatable = false, length = 16) String role;
    @Column(nullable = false, updatable = false, columnDefinition = "text") String content;
    @Column(name = "created_at", nullable = false, updatable = false) OffsetDateTime createdAt;
    // Retry identity retains client offset and nanosecond precision independently of display timestamps.
    @Column(name = "client_created_at", updatable = false, length = 64) String clientCreatedAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(updatable = false, columnDefinition = "jsonb") JsonNode metadata;
    protected AgentMessage() {}
    AgentMessage(AgentConversation conversation, MobileAgentChatResources.MessageRequest request) {
        id = request.id(); conversationId = conversation.id; sequence = conversation.nextSequence++;
        role = request.role(); content = request.content(); clientCreatedAt = request.createdAt() == null ? null : request.createdAt().toString();
        createdAt = request.createdAt() == null ? OffsetDateTime.now() : request.createdAt();
        metadata = request.metadata(); conversation.updatedAt = OffsetDateTime.now();
    }
}
