package com.diagramador.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.diagramador.generation.ProvisionalGenerationProfile.TypeMapping;
import java.util.Map;
import java.util.TreeMap;

/** Canonical, non-secret V1 options persisted with an immutable generation-profile revision. */
final class VersionedGenerationProfileConfiguration {

    static final String SUBSET_VERSION = "increment-2-subset-v1";
    private static final Map<String, TypeMapping> V1_PRIMITIVE_TYPES = Map.ofEntries(
            Map.entry("String", new TypeMapping("String", "VARCHAR(255)")),
            Map.entry("Integer", new TypeMapping("Integer", "INTEGER")),
            Map.entry("Long", new TypeMapping("Long", "BIGINT")),
            Map.entry("Boolean", new TypeMapping("Boolean", "BOOLEAN")),
            Map.entry("Double", new TypeMapping("Double", "DOUBLE PRECISION")),
            Map.entry("BigDecimal", new TypeMapping("java.math.BigDecimal", "NUMERIC(19, 2)")),
            Map.entry("LocalDate", new TypeMapping("java.time.LocalDate", "DATE")),
            Map.entry("LocalDateTime", new TypeMapping("java.time.LocalDateTime", "TIMESTAMP")),
            Map.entry("UUID", new TypeMapping("java.util.UUID", "UUID")));

    private VersionedGenerationProfileConfiguration() {
    }

    static JsonNode v1() {
        ObjectNode configuration = JsonNodeFactory.instance.objectNode();
        configuration.put("databaseEngine", "POSTGRESQL");

        ObjectNode subset = configuration.putObject("supportedUmlSubset");
        subset.put("version", SUBSET_VERSION);
        subset.set("classifierKinds", strings("CLASS", "ABSTRACT_CLASS", "PACKAGE"));
        subset.set("attributeKinds", strings("ATTRIBUTE"));
        subset.set("visibility", strings("PUBLIC", "PRIVATE"));
        subset.set("relationshipKinds", strings("ASSOCIATION", "AGGREGATION", "COMPOSITION"));
        subset.put("relationshipMultiplicityPolicy", "ONE_TO_MANY");
        subset.put("supportsOperations", true);
        subset.put("supportsParameters", true);
        subset.put("supportsDefaultValues", true);
        subset.put("supportsConstraints", false);
        subset.put("unsupportedFeaturesAreBlocking", true);

        ObjectNode mappings = configuration.putObject("primitiveTypeMappings");
        new TreeMap<>(V1_PRIMITIVE_TYPES).forEach((name, mapping) -> {
            ObjectNode type = mappings.putObject(name);
            type.put("javaTypeName", mapping.javaTypeName());
            type.put("sqlTypeName", mapping.sqlTypeName());
        });

        ObjectNode options = configuration.putObject("generationOptions");
        options.put("namingStrategy", "snake_case");
        options.put("packagePrefix", "");
        return configuration;
    }

    static JsonNode canonicalize(JsonNode value) {
        if (value == null || value.isNull() || value.isValueNode()) {
            return value == null ? JsonNodeFactory.instance.nullNode() : value.deepCopy();
        }
        if (value.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        TreeMap<String, JsonNode> sortedFields = new TreeMap<>();
        value.fields().forEachRemaining(entry -> sortedFields.put(entry.getKey(), entry.getValue()));
        sortedFields.forEach((name, child) -> result.set(name, canonicalize(child)));
        return result;
    }

    static String canonicalJson(JsonNode value, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(canonicalize(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Profile configuration cannot be serialized canonically", exception);
        }
    }

    static String hash(JsonNode value, ObjectMapper objectMapper) {
        return InputSnapshotFactory.sha256(canonicalJson(value, objectMapper));
    }

    static Map<String, TypeMapping> primitiveTypes(JsonNode configuration) {
        JsonNode mappings = configuration == null ? null : configuration.get("primitiveTypeMappings");
        if (mappings == null || !mappings.isObject()) {
            return Map.of();
        }
        Map<String, TypeMapping> result = new TreeMap<>();
        mappings.fields().forEachRemaining(entry -> {
            JsonNode mapping = entry.getValue();
            JsonNode javaType = mapping.get("javaTypeName");
            JsonNode sqlType = mapping.get("sqlTypeName");
            if (javaType != null && javaType.isTextual() && sqlType != null && sqlType.isTextual()) {
                result.put(entry.getKey(), new TypeMapping(javaType.textValue(), sqlType.textValue()));
            }
        });
        return Map.copyOf(result);
    }

    private static ArrayNode strings(String... values) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
