package com.diagramador.collaboration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Publishes full, client-replaceable snapshots of project presence. */
@Component
public class PresencePublisher {

    private final PresenceRegistry presenceRegistry;
    private final ProjectEventBus projectEventBus;

    public PresencePublisher(PresenceRegistry presenceRegistry, ProjectEventBus projectEventBus) {
        this.presenceRegistry = presenceRegistry;
        this.projectEventBus = projectEventBus;
    }

    public void publishSnapshot(UUID projectId) {
        List<Map<String, Object>> presence = new ArrayList<>();
        for (PresenceEntry entry : presenceRegistry.snapshot(projectId)) {
            Map<String, Object> person = new LinkedHashMap<>();
            person.put("userId", entry.userId());
            person.put("displayName", entry.displayName());
            person.put("color", entry.color());
            person.put("targetElementId", entry.targetElementId());
            // Session IDs are server-side and are deliberately excluded from the wire payload.
            presence.add(person);
        }
        projectEventBus.publish(ProjectEvent.of("presence.updated", projectId, Map.of("presence", presence)));
    }
}
