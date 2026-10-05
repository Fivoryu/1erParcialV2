package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.persistence.ViewStyle;
import com.diagramador.persistence.ViewStyleRepository;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * IA4-07 (style.set): upserts the per-view appearance of a relationship as one
 * {@code view_style} row. The whitelist rejects unknown payload fields; routing is
 * limited to STRAIGHT, FREE and ORTHOGONAL. The style travels separately from the
 * model semantics, so no semantic row is touched here.
 */
@Component
public class StyleSetHandler implements CommandHandler {

    private static final Set<String> FIELDS = Set.of("viewId", "targetId", "routing", "cornerRadius",
            "strokeWidth", "dashed");
    private static final String DEFAULT_ROUTING = "STRAIGHT";
    private static final int DEFAULT_CORNER_RADIUS = 0;
    private static final int DEFAULT_STROKE_WIDTH = 2;

    private final CommandHandlerSupport support;
    private final ViewStyleRepository viewStyleRepository;

    public StyleSetHandler(CommandHandlerSupport support, ViewStyleRepository viewStyleRepository) {
        this.support = support;
        this.viewStyleRepository = viewStyleRepository;
    }

    @Override
    public String type() {
        return "style.set";
    }

    @Override
    public Outcome apply(Context context) {
        JsonNode payload = support.payload(context, FIELDS);
        support.requireFields(payload, "viewId", "targetId");

        UUID viewId = support.uuid(payload, "viewId");
        UUID targetId = support.uuid(payload, "targetId");
        DiagramView view = support.diagramView(context.model(), viewId);
        UmlRelationship relationship = support.relationship(context.model(), targetId);

        String routing = routing(payload);
        int cornerRadius = nonNegativeOr(payload, "cornerRadius", DEFAULT_CORNER_RADIUS);
        int strokeWidth = strokeWidth(payload);
        boolean dashed = payload.hasNonNull("dashed") && support.bool(payload, "dashed");

        ViewStyle style = viewStyleRepository.findByViewIdAndTargetId(view.getId(), relationship.getId())
                .orElseGet(() -> ViewStyle.create(view, relationship.getId(), routing, cornerRadius,
                        strokeWidth, dashed));
        style.apply(routing, cornerRadius, strokeWidth, dashed);
        viewStyleRepository.save(style);
        return Outcome.none();
    }

    private String routing(JsonNode payload) {
        String value = payload.hasNonNull("routing")
                ? support.text(payload, "routing").toUpperCase(Locale.ROOT)
                : DEFAULT_ROUTING;
        if (!value.equals("STRAIGHT") && !value.equals("FREE") && !value.equals("ORTHOGONAL")) {
            support.invalid("Payload field 'routing' must be one of STRAIGHT, FREE, ORTHOGONAL");
        }
        return value;
    }

    private int nonNegativeOr(JsonNode payload, String field, int defaultValue) {
        if (!payload.hasNonNull(field)) {
            return defaultValue;
        }
        int value = support.integer(payload, field);
        if (value < 0) {
            support.invalid("Payload field '" + field + "' must be a non-negative integer");
        }
        return value;
    }

    private int strokeWidth(JsonNode payload) {
        if (!payload.hasNonNull("strokeWidth")) {
            return DEFAULT_STROKE_WIDTH;
        }
        int value = support.integer(payload, "strokeWidth");
        if (value < 1) {
            support.invalid("Payload field 'strokeWidth' must be a positive integer");
        }
        return value;
    }
}
