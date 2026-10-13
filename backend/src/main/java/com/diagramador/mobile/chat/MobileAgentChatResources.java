package com.diagramador.mobile.chat;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;
public final class MobileAgentChatResources {
    private MobileAgentChatResources() {}
    public record ConversationRequest(@NotNull UUID id, @NotBlank @Size(max=200) String title,
            @NotNull @Pattern(regexp="[a-f0-9]{64}") String contractHash) {}
    public record MessageRequest(@NotNull UUID id, @NotNull @Pattern(regexp="user|assistant|tool") String role,
            @NotBlank @Size(max=16000) String content, OffsetDateTime createdAt, JsonNode metadata) {}
    public record ConversationResource(UUID id, String title, String contractHash, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record MessageResource(UUID id, String role, String content, OffsetDateTime createdAt, long sequence, JsonNode metadata) {}
    public record PageResource<T>(List<T> items, String nextCursor) {}
}
