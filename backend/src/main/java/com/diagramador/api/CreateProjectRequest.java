package com.diagramador.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 200, message = "name must be at most 200 characters")
        String name,
        String description) {
}
