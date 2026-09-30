package com.diagramador.generation;

/** Value type for primitive mappings persisted as part of an immutable generation-profile revision. */
final class ProvisionalGenerationProfile {

    private ProvisionalGenerationProfile() {
    }

    record TypeMapping(String javaTypeName, String sqlTypeName) {
    }
}
