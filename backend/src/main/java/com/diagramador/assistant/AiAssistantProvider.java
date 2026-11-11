package com.diagramador.assistant;

@FunctionalInterface
public interface AiAssistantProvider {

    String draft(String modelContextJson, String utterance);

    /**
     * Drafts a plan from a request that also carries a photo of a class diagram (base64 plus MIME
     * type). Providers without a visual route ignore the image and draft from the text alone.
     */
    default String draft(String modelContextJson, String utterance, String imageBase64, String imageMime) {
        return draft(modelContextJson, utterance);
    }
}
