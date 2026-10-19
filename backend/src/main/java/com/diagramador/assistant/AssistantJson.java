package com.diagramador.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the first balanced JSON object from a provider response, tolerating fenced markdown
 * (the same contract the plan flow uses). Kept as a static utility so any assistant consumer can
 * share it without constructor wiring.
 */
public final class AssistantJson {

    private static final Pattern FENCED_JSON =
            Pattern.compile("```(?:json)?\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);
    private static final TypeReference<Map<String, Object>> OBJECT_TYPE = new TypeReference<>() {
    };

    private AssistantJson() {
    }

    public static Map<String, Object> extract(ObjectMapper objectMapper, String raw) {
        if (raw == null) {
            return null;
        }
        java.util.List<String> candidates = new java.util.ArrayList<>();
        Matcher fenced = FENCED_JSON.matcher(raw);
        if (fenced.find()) {
            candidates.add(fenced.group(1));
        }
        int start = raw.indexOf('{');
        if (start >= 0) {
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int index = start; index < raw.length(); index++) {
                char character = raw.charAt(index);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (character == '\\') {
                        escaped = true;
                    } else if (character == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (character == '"') {
                    inString = true;
                } else if (character == '{') {
                    depth++;
                } else if (character == '}') {
                    depth--;
                    if (depth == 0) {
                        candidates.add(raw.substring(start, index + 1));
                        break;
                    }
                }
            }
        }
        for (String candidate : candidates) {
            try {
                Map<String, Object> object = objectMapper.readValue(candidate, OBJECT_TYPE);
                if (object != null) {
                    return object;
                }
            } catch (Exception ignored) {
                // Try the next candidate; a total miss returns null (CA-13: nothing confirmed).
            }
        }
        return null;
    }
}
