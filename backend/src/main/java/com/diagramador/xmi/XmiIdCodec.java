package com.diagramador.xmi;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Reversible identifiers used by the measured Enterprise Architect dialect. */
public final class XmiIdCodec {

    private static final Pattern UUID_PARTS = Pattern.compile(
            "[0-9A-Fa-f]{8}_[0-9A-Fa-f]{4}_[0-9A-Fa-f]{4}_[0-9A-Fa-f]{4}_[0-9A-Fa-f]{12}");

    private XmiIdCodec() {
    }

    public static String classifierId(UUID id) {
        return encode("EAID_", id);
    }

    public static String packageId(UUID id) {
        return encode("EAPK_", id);
    }

    public static UUID classifierUuid(String id) {
        return decode("EAID_", id);
    }

    public static UUID packageUuid(String id) {
        return decode("EAPK_", id);
    }

    static String returnParameterId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("A canonical UUID is required.");
        }
        return derivedId("EAID_RT000000_", "return-parameter:" + id);
    }

    static String multiplicityValueId(String ownerId, String bound, boolean unlimited) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("An owning XMI id is required.");
        }
        if (!"lower".equals(bound) && !"upper".equals(bound)) {
            throw new IllegalArgumentException("A multiplicity bound must be lower or upper.");
        }
        String prefix = unlimited ? "EAID_LUN000001_" : "EAID_LI000001_";
        return derivedId(prefix, "multiplicity:" + ownerId + ":" + bound);
    }

    private static String derivedId(String prefix, String key) {
        UUID derived = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        String hex = derived.toString().replace("-", "").toUpperCase(Locale.ROOT);
        return prefix + hex.substring(8, 12) + "_" + hex.substring(12, 16) + "_"
                + hex.substring(16, 20) + "_" + hex.substring(20);
    }

    private static String encode(String prefix, UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("A canonical UUID is required.");
        }
        return prefix + id.toString().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static UUID decode(String prefix, String id) {
        if (id == null || !id.startsWith(prefix)) {
            throw new IllegalArgumentException("Identifier must start with " + prefix);
        }
        String value = id.substring(prefix.length());
        if (!UUID_PARTS.matcher(value).matches()) {
            throw new IllegalArgumentException("Identifier does not contain a canonical UUID.");
        }
        return UUID.fromString(value.replace('_', '-'));
    }
}
