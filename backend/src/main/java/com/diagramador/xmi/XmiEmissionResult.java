package com.diagramador.xmi;

import java.util.List;
import java.util.Objects;

/** The serialized XMI document and its measurable representational losses. */
public record XmiEmissionResult(String document, List<Loss> losses) {

    public XmiEmissionResult {
        Objects.requireNonNull(document, "document");
        losses = List.copyOf(losses);
    }

    /** A stable loss name and the number of affected constructs. */
    public record Loss(String name, int count) {

        public Loss {
            Objects.requireNonNull(name, "name");
            if (name.isBlank() || count < 1) {
                throw new IllegalArgumentException("A loss needs a name and a positive count.");
            }
        }
    }
}
