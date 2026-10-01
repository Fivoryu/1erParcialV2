package com.diagramador.identity;

import java.util.UUID;

/** Authenticated user details for the current request. */
public record CurrentUser(UUID userId, String username, String displayName) {
}
