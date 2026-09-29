package com.diagramador.command;

public record Precondition(String kind, Object expect, Object scope) {
}
