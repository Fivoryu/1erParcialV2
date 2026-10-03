package com.diagramador.collaboration;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-memory, single-instance project event channel; presence and events do not cross instances.
 */
@Component
public class ProjectEventBus {

    private final ConcurrentMap<UUID, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor;
    private final long timeoutMs;

    public ProjectEventBus(@Value("${diagrammer.events.heartbeat-ms:15000}") long heartbeatMs,
            @Value("${diagrammer.events.timeout-ms:1800000}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "project-event-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeats, heartbeatMs, heartbeatMs,
                TimeUnit.MILLISECONDS);
    }

    public SseEmitter subscribe(UUID projectId) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        emitter.onCompletion(() -> remove(projectId, emitter));
        emitter.onTimeout(() -> remove(projectId, emitter));
        emitter.onError(error -> remove(projectId, emitter));
        subscribers.compute(projectId, (id, current) -> {
            Set<SseEmitter> projectSubscribers = current == null ? ConcurrentHashMap.newKeySet() : current;
            projectSubscribers.add(emitter);
            return projectSubscribers;
        });
        return emitter;
    }

    public void publish(ProjectEvent event) {
        Set<SseEmitter> projectSubscribers = subscribers.get(event.projectId());
        if (projectSubscribers == null) {
            return;
        }
        for (SseEmitter emitter : projectSubscribers) {
            send(event.projectId(), emitter,
                    SseEmitter.event().name(event.type()).data(event, MediaType.APPLICATION_JSON));
        }
    }

    public int subscriberCount(UUID projectId) {
        Set<SseEmitter> projectSubscribers = subscribers.get(projectId);
        return projectSubscribers == null ? 0 : projectSubscribers.size();
    }

    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    private void sendHeartbeats() {
        subscribers.forEach((projectId, projectSubscribers) -> {
            for (SseEmitter emitter : projectSubscribers) {
                send(projectId, emitter, SseEmitter.event().comment("heartbeat"));
            }
        });
    }

    private void send(UUID projectId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            synchronized (emitter) {
                emitter.send(event);
            }
        } catch (IOException | RuntimeException ignored) {
            remove(projectId, emitter);
        }
    }

    private void remove(UUID projectId, SseEmitter emitter) {
        subscribers.computeIfPresent(projectId, (id, projectSubscribers) -> {
            projectSubscribers.remove(emitter);
            return projectSubscribers.isEmpty() ? null : projectSubscribers;
        });
    }
}
