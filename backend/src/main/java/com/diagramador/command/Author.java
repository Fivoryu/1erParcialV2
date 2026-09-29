package com.diagramador.command;

import java.util.UUID;

public record Author(UUID userId, String externalSubject) {
}
