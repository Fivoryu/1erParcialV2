package com.diagramador.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class AuthResources {

    private AuthResources() {
    }

    public record RegisterRequest(
            @NotBlank(message = "username must not be blank") String username,
            @NotBlank(message = "password must not be blank")
            @Size(min = 8, max = 200, message = "password must be between 8 and 200 characters") String password,
            @NotBlank(message = "displayName must not be blank") String displayName,
            String email) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record UserResource(UUID id, String username, String displayName, String email) {
    }

    public record LoginResource(String token, OffsetDateTime expiresAt, UserResource user) {
    }
}
