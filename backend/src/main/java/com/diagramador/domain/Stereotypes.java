package com.diagramador.domain;

import java.util.Locale;

/** Shared reading of the fixed stereotype lists (ClassifierStereotype, AttributeStereotype, OperationStereotype). */
public final class Stereotypes {

    private Stereotypes() {
    }

    /**
     * The canonical (upper case) name of {@code written} in the {@code list} enum, or null when it is blank or not
     * one of its constants. Case and surrounding spaces are ignored.
     */
    public static <E extends Enum<E>> String canonical(Class<E> list, String written) {
        if (written == null || written.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(list, written.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Only a class (plain or abstract) may carry a classifier stereotype; interfaces, enumerations, packages may not. */
    public static boolean classifierMayCarryOne(ClassifierKind kind) {
        return kind == ClassifierKind.CLASS || kind == ClassifierKind.ABSTRACT_CLASS;
    }
}
