package com.diagramador.collaboration;

import com.diagramador.domain.TargetType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Structured details about the lease currently recorded for a model. */
public record EditingLeaseConflict(String editorDisplayName, TargetType targetType, UUID targetId,
        boolean reclaimable, int retryAfterSeconds) {

    /** Builds the stable API detail object shared by lease conflicts and export rejections. */
    public LinkedHashMap<String, Object> toDetails() {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("editorDisplayName", editorDisplayName);
        details.put("targetType", targetType == null ? null : targetType.name());
        details.put("targetId", targetId);
        details.put("reclaimable", reclaimable);
        details.put("retryAfterSeconds", (long) retryAfterSeconds);
        return details;
    }

    public static EditingLeaseConflict fromDetails(Map<String, Object> details) {
        Object targetType = details.get("targetType");
        return new EditingLeaseConflict((String) details.get("editorDisplayName"),
                targetType == null ? null : TargetType.valueOf((String) targetType),
                (UUID) details.get("targetId"), (Boolean) details.get("reclaimable"),
                ((Number) details.get("retryAfterSeconds")).intValue());
    }
}
