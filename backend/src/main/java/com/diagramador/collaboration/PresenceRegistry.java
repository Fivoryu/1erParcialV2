package com.diagramador.collaboration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Ephemeral, in-memory presence for one application instance; it has no persistence, database, or
 * migration, so presence does not cross application instances. Sessions that die without calling
 * {@link #leave(UUID, UUID)} are removed by the channel's completion callback (C11), not by a clock
 * sweep here.
 *
 * <p>Colors come from a fixed palette and UUID-bit mixer. The 10-color palette permits at most 10
 * distinct colors, so more than 10 simultaneous users guarantees a collision; the same user always
 * maps to the same color.
 */
@Component
public class PresenceRegistry {

    private static final List<String> COLOR_PALETTE = List.of(
            "#1F77B4", // blue
            "#FF7F0E", // orange
            "#2CA02C", // green
            "#D62728", // red
            "#9467BD", // purple
            "#8C564B", // brown
            "#E377C2", // pink
            "#7F7F7F", // gray
            "#BCBD22", // olive
            "#17BECF"  // cyan
    );

    private final ConcurrentMap<UUID, ConcurrentMap<UUID, PresenceEntry>> projects = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> latestTimestamp = new AtomicReference<>(Instant.MIN);

    /**
     * Adds a session, or refreshes the same user's existing entry without changing its original join time or target.
     */
    public PresenceEntry join(UUID projectId, UUID sessionId, UUID userId, String displayName) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(displayName, "displayName");

        AtomicReference<PresenceEntry> joined = new AtomicReference<>();
        projects.compute(projectId, (id, currentSessions) -> {
            ConcurrentMap<UUID, PresenceEntry> sessions = currentSessions == null
                    ? new ConcurrentHashMap<>()
                    : currentSessions;
            sessions.compute(sessionId, (session, existing) -> {
                Instant now = nextTimestamp();
                boolean sameUser = existing != null && existing.userId().equals(userId);
                PresenceEntry entry = new PresenceEntry(sessionId, userId, displayName, colorFor(userId),
                        sameUser ? existing.targetElementId() : null,
                        sameUser ? existing.joinedAt() : now, now);
                joined.set(entry);
                return entry;
            });
            return sessions;
        });
        return joined.get();
    }

    /** Removes only the specified session; other sessions belonging to the same user remain present. */
    public void leave(UUID projectId, UUID sessionId) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(sessionId, "sessionId");
        projects.computeIfPresent(projectId, (id, sessions) -> {
            sessions.remove(sessionId);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    /** Updates a live session's target and activity time; returns false when the session is absent. */
    public boolean touch(UUID projectId, UUID sessionId, UUID targetElementId) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(sessionId, "sessionId");

        AtomicBoolean updated = new AtomicBoolean();
        projects.computeIfPresent(projectId, (id, sessions) -> {
            sessions.computeIfPresent(sessionId, (session, existing) -> {
                updated.set(true);
                return new PresenceEntry(existing.sessionId(), existing.userId(), existing.displayName(),
                        existing.color(), targetElementId, existing.joinedAt(), nextTimestamp());
            });
            return sessions;
        });
        return updated.get();
    }

    /** Refreshes the last-seen time of a live session; absent sessions are ignored. */
    public void refresh(UUID projectId, UUID sessionId) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(sessionId, "sessionId");
        projects.computeIfPresent(projectId, (id, sessions) -> {
            sessions.computeIfPresent(sessionId, (session, existing) -> new PresenceEntry(
                    existing.sessionId(), existing.userId(), existing.displayName(), existing.color(),
                    existing.targetElementId(), existing.joinedAt(), nextTimestamp()));
            return sessions;
        });
    }

    /**
     * Returns one immutable entry per user, selecting that user's most recently active session, sorted
     * by user ID.
     */
    public List<PresenceEntry> snapshot(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        AtomicReference<List<PresenceEntry>> snapshot = new AtomicReference<>(List.of());
        projects.computeIfPresent(projectId, (id, sessions) -> {
            Map<UUID, PresenceEntry> latestByUser = new HashMap<>();
            for (PresenceEntry entry : sessions.values()) {
                latestByUser.merge(entry.userId(), entry,
                        (first, second) -> first.lastSeenAt().isAfter(second.lastSeenAt()) ? first : second);
            }
            ArrayList<PresenceEntry> sorted = new ArrayList<>(latestByUser.values());
            sorted.sort(Comparator.comparing(PresenceEntry::userId));
            snapshot.set(List.copyOf(sorted));
            return sessions;
        });
        return snapshot.get();
    }

    /** Returns the number of live sessions, before snapshot collapse by user. */
    public int sessionCount(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        AtomicInteger count = new AtomicInteger();
        projects.computeIfPresent(projectId, (id, sessions) -> {
            count.set(sessions.size());
            return sessions;
        });
        return count.get();
    }

    /**
     * Returns a deterministic {@code #RRGGBB} color derived only from a UUID's 128 bits. The UUID halves
     * are XORed (rotating the least-significant half left by 29 bits), then passed through the SplitMix64
     * finalizer; its unsigned remainder selects one of the ten fixed palette entries.
     */
    public String colorFor(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        long mixed = userId.getMostSignificantBits() ^ Long.rotateLeft(userId.getLeastSignificantBits(), 29);
        mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
        mixed ^= mixed >>> 31;
        int index = (int) Long.remainderUnsigned(mixed, COLOR_PALETTE.size());
        return COLOR_PALETTE.get(index);
    }

    private Instant nextTimestamp() {
        Instant observed = Instant.now();
        return latestTimestamp.updateAndGet(previous -> observed.isAfter(previous) ? observed : previous.plusNanos(1));
    }
}
