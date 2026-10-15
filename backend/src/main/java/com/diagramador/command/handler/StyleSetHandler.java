package com.diagramador.command.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.diagramador.command.CommandHandler;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.UmlRelationship;
import com.diagramador.persistence.ViewStyle;
import com.diagramador.persistence.ViewStyleRepository;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * IA4-07 (style.set): upserts the per-view appearance of a relationship as one
 * {@code view_style} row. The whitelist rejects unknown payload fields; routing is
 * limited to STRAIGHT, FREE and ORTHOGONAL. ROR-05 adds {@code routeAdjustment}: a
 * frame-relative orthogonal adjustment ({\@code axis, corridors, levels}) validated in
 * shape and range, rejected unless routing is ORTHOGONAL, and cleared by an absent or
 * JSON-null value. ANC-01 adds the optional nested {@code anchors} pair (source/target,
 * each exactly {@code side} + integer {@code offset}), validated with the same strictness
 * and stored inside the same adjustment JSON. The style travels separately from the model semantics, so no
 * semantic row is touched here.
 */
@Component
public class StyleSetHandler implements CommandHandler {

    private static final Set<String> FIELDS = Set.of("viewId", "targetId", "routing", "cornerRadius",
            "strokeWidth", "dashed", "routeAdjustment");
    private static final Set<String> ADJUSTMENT_KEYS = Set.of("axis", "corridors", "levels", "anchors");
    private static final Set<String> ANCHOR_KEYS = Set.of("source", "target");
    private static final Set<String> ANCHOR_END_KEYS = Set.of("side", "offset");
    private static final Set<String> ANCHOR_SIDES = Set.of("N", "E", "S", "W");
    private static final String DEFAULT_ROUTING = "STRAIGHT";
    private static final int DEFAULT_CORNER_RADIUS = 0;
    private static final int DEFAULT_STROKE_WIDTH = 2;
    private static final int MAX_CORRIDORS = 32;
    private static final int MAX_ABS_ADJUSTMENT = 100000;

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
        JsonNode routeAdjustment = routeAdjustment(payload, routing);

        ViewStyle style = viewStyleRepository.findByViewIdAndTargetId(view.getId(), relationship.getId())
                .orElseGet(() -> ViewStyle.create(view, relationship.getId(), routing, cornerRadius,
                        strokeWidth, dashed, routeAdjustment));
        style.apply(routing, cornerRadius, strokeWidth, dashed, routeAdjustment);
        viewStyleRepository.save(style);
        return Outcome.none();
    }

    /**
     * ROR-02/ROR-05: absent or JSON null clears the adjustment; otherwise the object must hold
     * exactly axis, corridors and levels, with 1..32 corridor integers, exactly corridors-1 level
     * integers, all within ±100000, and routing must be ORTHOGONAL.
     */
    private JsonNode routeAdjustment(JsonNode payload, String routing) {
        if (!payload.hasNonNull("routeAdjustment")) {
            return null;
        }
        JsonNode value = payload.get("routeAdjustment");
        if (!value.isObject()) {
            support.invalid("Payload field 'routeAdjustment' must be an object or null");
        }
        Set<String> unknown = new HashSet<>();
        value.fieldNames().forEachRemaining(name -> {
            if (!ADJUSTMENT_KEYS.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            support.invalid("Payload field 'routeAdjustment' contains unsupported field(s): " + unknown);
        }
        JsonNode axis = value.get("axis");
        if (axis == null || !axis.isTextual() || !(axis.asText().equals("H") || axis.asText().equals("V"))) {
            support.invalid("Payload field 'routeAdjustment' axis must be H or V");
        }
        JsonNode corridors = value.get("corridors");
        if (corridors == null || !corridors.isArray() || corridors.size() < 1 || corridors.size() > MAX_CORRIDORS) {
            support.invalid("Payload field 'routeAdjustment' corridors must hold 1 to " + MAX_CORRIDORS
                    + " integers");
        }
        JsonNode levels = value.get("levels");
        if (levels == null || !levels.isArray() || levels.size() != corridors.size() - 1) {
            support.invalid("Payload field 'routeAdjustment' levels must hold exactly corridors.length - 1 integers");
        }
        for (JsonNode corridor : corridors) {
            requireAdjustmentInteger(corridor);
        }
        for (JsonNode level : levels) {
            requireAdjustmentInteger(level);
        }
        JsonNode anchors = value.get("anchors");
        if (anchors != null && !anchors.isNull()) {
            validateAnchors(anchors);
        }
        if (!"ORTHOGONAL".equals(routing)) {
            support.invalid("Payload field 'routeAdjustment' requires routing ORTHOGONAL");
        }
        return value;
    }

    /**
     * ANC-01: the anchors pair must hold exactly source and target, and each end exactly
     * side (N/E/S/W) and an integer offset within ±100000; a half-specified pair is rejected.
     */
    private void validateAnchors(JsonNode anchors) {
        if (!anchors.isObject()) {
            support.invalid("Payload field 'routeAdjustment' anchors must be an object or null");
        }
        Set<String> unknown = new HashSet<>();
        anchors.fieldNames().forEachRemaining(name -> {
            if (!ANCHOR_KEYS.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            support.invalid("Payload field 'routeAdjustment' anchors contains unsupported field(s): " + unknown);
        }
        requireAnchorEnd(anchors.get("source"), "source");
        requireAnchorEnd(anchors.get("target"), "target");
    }

    private void requireAnchorEnd(JsonNode end, String name) {
        if (end == null || end.isNull() || !end.isObject()) {
            support.invalid("Payload field 'routeAdjustment' anchors " + name + " must be an object");
        }
        Set<String> unknown = new HashSet<>();
        end.fieldNames().forEachRemaining(field -> {
            if (!ANCHOR_END_KEYS.contains(field)) {
                unknown.add(field);
            }
        });
        if (!unknown.isEmpty()) {
            support.invalid("Payload field 'routeAdjustment' anchors " + name
                    + " contains unsupported field(s): " + unknown);
        }
        JsonNode side = end.get("side");
        if (side == null || !side.isTextual() || !ANCHOR_SIDES.contains(side.asText())) {
            support.invalid("Payload field 'routeAdjustment' anchors " + name
                    + " side must be one of N, E, S, W");
        }
        JsonNode offset = end.get("offset");
        if (offset == null || !offset.isIntegralNumber() || !offset.canConvertToInt()
                || Math.abs(offset.asInt()) > MAX_ABS_ADJUSTMENT) {
            support.invalid("Payload field 'routeAdjustment' anchors " + name + " offset must be an integer within ±"
                    + MAX_ABS_ADJUSTMENT);
        }
    }

    private void requireAdjustmentInteger(JsonNode entry) {
        if (!entry.isIntegralNumber() || !entry.canConvertToInt()
                || Math.abs(entry.asInt()) > MAX_ABS_ADJUSTMENT) {
            support.invalid("Payload field 'routeAdjustment' values must be integers within ±"
                    + MAX_ABS_ADJUSTMENT);
        }
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
