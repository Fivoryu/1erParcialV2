package com.diagramador.collaboration;

import java.lang.reflect.Array;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Value envelope for project-scoped collaboration events. Payload containers are recursively copied,
 * but non-container leaf objects are retained by reference. Callers must keep those leaves immutable
 * through delivery because an as-yet-unserialized subscriber may observe mutations made after publish
 * returns.
 */
public record ProjectEvent(String schemaVersion, String type, UUID projectId, OffsetDateTime occurredAt,
        Map<String, Object> payload) {

    public ProjectEvent {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        payload = immutableObject(Objects.requireNonNull(payload, "payload"));
    }

    public static ProjectEvent of(String type, UUID projectId, Map<String, Object> payload) {
        return new ProjectEvent("1.0", type, projectId, OffsetDateTime.now(), payload);
    }

    private static Map<String, Object> immutableObject(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String stringKey)) {
                throw new IllegalArgumentException("Event payload object keys must be strings");
            }
            copy.put(stringKey, immutableValue(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return immutableObject(map);
        }
        if (value instanceof Collection<?> collection) {
            ArrayList<Object> copy = new ArrayList<>(collection.size());
            collection.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            ArrayList<Object> copy = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                copy.add(immutableValue(Array.get(value, index)));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
