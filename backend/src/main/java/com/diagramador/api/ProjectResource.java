package com.diagramador.api;

import java.util.UUID;

public record ProjectResource(UUID id, String name, String description) {
}
