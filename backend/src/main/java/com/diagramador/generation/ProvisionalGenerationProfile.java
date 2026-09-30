package com.diagramador.generation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Provisional, single-place V1 profile decisions used while constructing the IR. */
final class ProvisionalGenerationProfile {

    /*
     * This provisional profile decision mirrors the spike's primitive mapping table. Task T4 moves this exact
     * table behind the versioned profile without changing its contents.
     */
    private static final Map<String, TypeMapping> PRIMITIVE_TYPES;

    static {
        Map<String, TypeMapping> mappings = new LinkedHashMap<>();
        mappings.put("String", new TypeMapping("String", "VARCHAR(255)"));
        mappings.put("Integer", new TypeMapping("Integer", "INTEGER"));
        mappings.put("Long", new TypeMapping("Long", "BIGINT"));
        mappings.put("Boolean", new TypeMapping("Boolean", "BOOLEAN"));
        mappings.put("Double", new TypeMapping("Double", "DOUBLE PRECISION"));
        mappings.put("BigDecimal", new TypeMapping("java.math.BigDecimal", "NUMERIC(19, 2)"));
        mappings.put("LocalDate", new TypeMapping("java.time.LocalDate", "DATE"));
        mappings.put("LocalDateTime", new TypeMapping("java.time.LocalDateTime", "TIMESTAMP"));
        mappings.put("UUID", new TypeMapping("java.util.UUID", "UUID"));
        PRIMITIVE_TYPES = Collections.unmodifiableMap(mappings);
    }

    private ProvisionalGenerationProfile() {
    }

    static TypeMapping primitiveType(String name) {
        return PRIMITIVE_TYPES.get(name);
    }

    record TypeMapping(String javaTypeName, String sqlTypeName) {
    }
}
