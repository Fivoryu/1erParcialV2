package com.diagramador.notifications;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Delivers pending push events with backoff. Payloads carry identifiers only, never diagram content. */
@Component
public class PushOutboxProcessor {
    static final int MAX_ATTEMPTS = 10;
    private static final int BATCH = 10;
    private final JdbcTemplate jdbc;
    private final PushSender sender;

    public PushOutboxProcessor(JdbcTemplate jdbc, PushSender sender) {
        this.jdbc = jdbc;
        this.sender = sender;
    }

    private record Event(UUID id, UUID project, UUID request, UUID recipient, String type, int attempts) { }
    private record Device(UUID id, String token) { }

    @Scheduled(fixedDelayString = "${diagrammer.push.poll-millis:15000}")
    public void poll() {
        process();
    }

    /** Rows are claimed with SKIP LOCKED so concurrent instances never send the same event twice. */
    @Transactional
    public int process() {
        List<Event> events = jdbc.query("""
                SELECT id, project_id, merge_request_id, recipient_id, event_type, attempts
                FROM diagrammer.push_outbox
                WHERE delivered_at IS NULL AND abandoned_at IS NULL AND next_attempt_at <= now()
                ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, n) -> new Event(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getInt(6)), BATCH);
        for (Event event : events) deliver(event);
        return events.size();
    }

    private void deliver(Event event) {
        List<Device> devices = jdbc.query("SELECT device_id, token FROM diagrammer.push_device WHERE user_id = ?",
                (rs, n) -> new Device(rs.getObject(1, UUID.class), rs.getString(2)), event.recipient());
        boolean retry = false;
        for (Device device : devices) {
            var result = sender.send(device.token(), title(event.type()), "Abre la app para revisar los cambios.",
                    Map.of("type", event.type(), "projectId", event.project().toString(),
                            "mergeRequestId", event.request().toString()));
            if (result == PushSender.Result.INVALID_TOKEN) {
                jdbc.update("DELETE FROM diagrammer.push_device WHERE device_id = ?", device.id());
            } else if (result == PushSender.Result.RETRY) {
                retry = true;
            }
        }
        if (!retry) {
            jdbc.update("UPDATE diagrammer.push_outbox SET delivered_at = now() WHERE id = ?", event.id());
        } else if (event.attempts() + 1 >= MAX_ATTEMPTS) {
            jdbc.update("UPDATE diagrammer.push_outbox SET attempts = attempts + 1, abandoned_at = now() WHERE id = ?",
                    event.id());
        } else {
            long seconds = Math.min(3600L, 30L << Math.min(event.attempts(), 7));
            jdbc.update("""
                    UPDATE diagrammer.push_outbox SET attempts = attempts + 1,
                        next_attempt_at = now() + make_interval(secs => ?) WHERE id = ?
                    """, seconds, event.id());
        }
    }

    private static String title(String type) {
        return "REVIEW_CHANGES".equals(type) ? "Cambios solicitados" : "Nueva solicitud de fusión";
    }
}
