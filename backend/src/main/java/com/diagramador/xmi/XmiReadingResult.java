package com.diagramador.xmi;

import com.diagramador.api.ModelResource;
import com.diagramador.domain.NavigationKind;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** The semantic model, EA diagram details, and measurable parts the reader could not interpret. */
public record XmiReadingResult(
        ModelResource model,
        List<Diagram> diagrams,
        Map<UUID, String> documentation,
        List<Disagreement> disagreements,
        List<Loss> losses) {

    public XmiReadingResult {
        Objects.requireNonNull(model, "model");
        diagrams = List.copyOf(diagrams);
        documentation = Collections.unmodifiableMap(new TreeMap<>(documentation));
        disagreements = List.copyOf(disagreements);
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

    /** A concrete conflict retained alongside its aggregate loss count. */
    public record Disagreement(String kind, String subjectId, String semanticValue, String extensionValue) {

        public Disagreement {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(semanticValue, "semanticValue");
            Objects.requireNonNull(extensionValue, "extensionValue");
        }
    }

    public record Diagram(UUID id, String name, boolean isDefault,
            List<FigurePlacement> figures, List<ConnectorPlacement> connectors) {

        public Diagram {
            Objects.requireNonNull(id, "id");
            figures = List.copyOf(figures);
            connectors = List.copyOf(connectors);
        }
    }

    public record FigurePlacement(String subjectId, UUID classifierId, String duid,
            int left, int top, int right, int bottom, int sequence) {

        public FigurePlacement {
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(classifierId, "classifierId");
        }
    }

    public record ConnectorPlacement(String subjectId, UUID relationshipId, String mode,
            String sourceDuid, String targetDuid, String path,
            ConnectorEnd source, ConnectorEnd target) {

        public ConnectorPlacement {
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(relationshipId, "relationshipId");
        }
    }

    public record ConnectorEnd(String classifierIdRef, UUID classifierId, String role,
            String visibility, String multiplicity, NavigationKind navigability) {

        public ConnectorEnd {
            Objects.requireNonNull(classifierIdRef, "classifierIdRef");
            Objects.requireNonNull(classifierId, "classifierId");
        }
    }
}
