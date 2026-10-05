package com.diagramador.assistant;

@FunctionalInterface
public interface AiAssistantProvider {

    String draft(String modelContextJson, String utterance);
}
