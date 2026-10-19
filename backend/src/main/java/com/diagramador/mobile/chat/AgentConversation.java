package com.diagramador.mobile.chat;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_conversation", schema = "diagrammer")
public class AgentConversation {
    @Id @Column(name = "conversation_id") UUID id;
    @Column(name = "user_id", nullable = false, updatable = false) UUID userId;
    @Column(name = "project_id", nullable = false, updatable = false) UUID projectId;
    @Column(name = "deployment_id", nullable = false, updatable = false) UUID deploymentId;
    @Column(name = "contract_hash", nullable = false, updatable = false, length = 64) String contractHash;
    @Column(nullable = false, length = 200) String title;
    @Column(name = "created_at", nullable = false, updatable = false) OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) OffsetDateTime updatedAt;
    @Column(name = "next_sequence", nullable = false) long nextSequence;
    protected AgentConversation() {}
    AgentConversation(UUID id, UUID userId, UUID projectId, UUID deploymentId, String hash, String title) {
        this.id = id; this.userId = userId; this.projectId = projectId; this.deploymentId = deploymentId;
        this.contractHash = hash; this.title = title; createdAt = OffsetDateTime.now(); updatedAt = createdAt; nextSequence = 1;
    }
}
