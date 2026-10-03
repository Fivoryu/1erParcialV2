package com.diagramador.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PresenceRegistryTest {

    private static final UUID PROJECT = new UUID(0L, 1L);

    @Test
    void colorIsStableAcrossCallsRegistriesAndJoinOrder() {
        UUID ana = new UUID(0x123456789abcdef0L, 0x0123456789abcdefL);
        UUID bea = new UUID(0x0fedcba987654321L, 0x1020304050607080L);
        PresenceRegistry forward = new PresenceRegistry();
        PresenceRegistry reverse = new PresenceRegistry();

        String anaColor = forward.colorFor(ana);
        assertThat(forward.colorFor(ana)).isEqualTo(anaColor);
        forward.join(PROJECT, new UUID(0L, 11L), ana, "Ana");
        forward.join(PROJECT, new UUID(0L, 12L), bea, "Bea");
        reverse.join(PROJECT, new UUID(0L, 22L), bea, "Bea");
        reverse.join(PROJECT, new UUID(0L, 21L), ana, "Ana");

        assertThat(reverse.colorFor(ana)).isEqualTo(anaColor);
        assertThat(entryFor(forward.snapshot(PROJECT), ana).color()).isEqualTo(anaColor);
        assertThat(entryFor(reverse.snapshot(PROJECT), ana).color()).isEqualTo(anaColor);
        assertThat(forward.colorFor(bea)).isEqualTo(reverse.colorFor(bea));
        assertThat(anaColor).matches("#[0-9A-F]{6}");
    }

    @Test
    void twoSessionsCollapseToMostRecentlyActiveUserAndRepeatedJoinIsIdempotent() {
        PresenceRegistry registry = new PresenceRegistry();
        UUID user = new UUID(0L, 100L);
        UUID firstSession = new UUID(0L, 101L);
        UUID secondSession = new UUID(0L, 102L);
        UUID firstTarget = new UUID(0L, 201L);
        UUID secondTarget = new UUID(0L, 202L);

        PresenceEntry first = registry.join(PROJECT, firstSession, user, "Ana");
        assertThat(registry.touch(PROJECT, firstSession, firstTarget)).isTrue();
        PresenceEntry refreshed = registry.join(PROJECT, firstSession, user, "Ana");
        assertThat(registry.sessionCount(PROJECT)).isEqualTo(1);
        assertThat(refreshed.joinedAt()).isEqualTo(first.joinedAt());
        assertThat(refreshed.targetElementId()).isEqualTo(firstTarget);

        registry.join(PROJECT, secondSession, user, "Ana");
        assertThat(registry.touch(PROJECT, secondSession, secondTarget)).isTrue();
        assertThat(registry.sessionCount(PROJECT)).isEqualTo(2);
        assertThat(registry.snapshot(PROJECT)).hasSize(1);
        PresenceEntry collapsed = entryFor(registry.snapshot(PROJECT), user);
        assertThat(collapsed.sessionId()).isEqualTo(secondSession);
        assertThat(collapsed.targetElementId()).isEqualTo(secondTarget);

        UUID latestTarget = new UUID(0L, 203L);
        assertThat(registry.touch(PROJECT, firstSession, latestTarget)).isTrue();
        collapsed = entryFor(registry.snapshot(PROJECT), user);
        assertThat(collapsed.sessionId()).isEqualTo(firstSession);
        assertThat(collapsed.targetElementId()).isEqualTo(latestTarget);
    }

    @Test
    void reboundSessionStartsFreshWhenUserChanges() {
        PresenceRegistry registry = new PresenceRegistry();
        UUID session = new UUID(0L, 250L);
        UUID previousUser = new UUID(0L, 251L);
        UUID reboundUser = new UUID(0L, 252L);
        UUID target = new UUID(0L, 253L);

        registry.join(PROJECT, session, previousUser, "Ana");
        assertThat(registry.touch(PROJECT, session, target)).isTrue();

        PresenceEntry rebound = registry.join(PROJECT, session, reboundUser, "Bea");
        List<PresenceEntry> snapshot = registry.snapshot(PROJECT);

        assertThat(rebound.targetElementId())
                .as("a rebound session must not inherit the previous user's target")
                .isNull();
        assertThat(entryFor(snapshot, reboundUser).targetElementId()).isNull();
        assertThat(snapshot).noneMatch(entry -> entry.sessionId().equals(session)
                && entry.userId().equals(previousUser));
    }

    @Test
    void leavingOneSessionKeepsUserPresentAndLeavingLastRemovesThem() {
        PresenceRegistry registry = new PresenceRegistry();
        UUID user = new UUID(0L, 300L);
        UUID firstSession = new UUID(0L, 301L);
        UUID secondSession = new UUID(0L, 302L);
        registry.join(PROJECT, firstSession, user, "Ana");
        registry.join(PROJECT, secondSession, user, "Ana");

        registry.leave(PROJECT, firstSession);
        assertThat(registry.snapshot(PROJECT)).hasSize(1);
        assertThat(entryFor(registry.snapshot(PROJECT), user).sessionId()).isEqualTo(secondSession);
        assertThat(registry.sessionCount(PROJECT)).isEqualTo(1);

        registry.leave(PROJECT, secondSession);
        assertThat(registry.snapshot(PROJECT)).isEmpty();
        assertThat(registry.sessionCount(PROJECT)).isZero();
    }

    @Test
    void touchingAbsentSessionDoesNotChangePresence() {
        PresenceRegistry registry = new PresenceRegistry();
        registry.join(PROJECT, new UUID(0L, 401L), new UUID(0L, 400L), "Ana");
        List<PresenceEntry> before = registry.snapshot(PROJECT);
        int countBefore = registry.sessionCount(PROJECT);

        assertThat(registry.touch(PROJECT, new UUID(0L, 499L), new UUID(0L, 900L))).isFalse();
        assertThat(registry.snapshot(PROJECT)).isEqualTo(before);
        assertThat(registry.sessionCount(PROJECT)).isEqualTo(countBefore);
    }

    @Test
    void snapshotIsImmutableAndDeterministicallySorted() {
        PresenceRegistry registry = new PresenceRegistry();
        UUID largerUser = new UUID(0L, 20L);
        UUID smallerUser = new UUID(0L, 3L);
        registry.join(PROJECT, new UUID(0L, 501L), largerUser, "Bea");
        registry.join(PROJECT, new UUID(0L, 502L), smallerUser, "Ana");

        List<PresenceEntry> first = registry.snapshot(PROJECT);
        List<PresenceEntry> second = registry.snapshot(PROJECT);
        assertThat(first).isEqualTo(second).isSortedAccordingTo(Comparator.comparing(PresenceEntry::userId));
        assertThatThrownBy(() -> first.add(first.get(0))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void refreshUpdatesExistingSessionAndIsANoOpForAnAbsentSession() {
        PresenceRegistry registry = new PresenceRegistry();
        UUID session = new UUID(0L, 601L);
        UUID user = new UUID(0L, 600L);
        PresenceEntry joined = registry.join(PROJECT, session, user, "Ana");

        registry.refresh(PROJECT, session);
        PresenceEntry refreshed = entryFor(registry.snapshot(PROJECT), user);
        assertThat(refreshed.lastSeenAt()).isAfter(joined.lastSeenAt());

        registry.refresh(PROJECT, new UUID(0L, 699L));
        assertThat(registry.sessionCount(PROJECT)).isEqualTo(1);
        assertThat(registry.snapshot(PROJECT)).containsExactly(refreshed);
    }

    @Test
    void concurrentSessionOperationsRemainCoherentAndDoNotLeak() throws Exception {
        PresenceRegistry registry = new PresenceRegistry();
        int workerCount = 8;
        int iterations = 40;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        CyclicBarrier phaseBarrier = new CyclicBarrier(workerCount);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int worker = 0; worker < workerCount; worker++) {
                int workerId = worker;
                UUID session = new UUID(1L, workerId + 1L);
                UUID user = new UUID(2L, workerId + 1L);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        registry.join(PROJECT, session, user, "User " + workerId);
                        assertThat(registry.touch(PROJECT, session, new UUID(3L, iteration + 1L))).isTrue();
                        registry.refresh(PROJECT, session);
                        phaseBarrier.await(5, TimeUnit.SECONDS);

                        assertThat(registry.sessionCount(PROJECT)).isEqualTo(workerCount);
                        List<PresenceEntry> snapshot = registry.snapshot(PROJECT);
                        assertThat(snapshot).hasSize(workerCount)
                                .isSortedAccordingTo(Comparator.comparing(PresenceEntry::userId));
                        assertThat(snapshot.stream().map(PresenceEntry::userId).distinct().count())
                                .isEqualTo(workerCount);
                        phaseBarrier.await(5, TimeUnit.SECONDS);

                        registry.leave(PROJECT, session);
                        phaseBarrier.await(5, TimeUnit.SECONDS);
                        assertThat(registry.sessionCount(PROJECT)).isZero();
                        assertThat(registry.snapshot(PROJECT)).isEmpty();
                        phaseBarrier.await(5, TimeUnit.SECONDS);
                    }
                    return null;
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            assertThat(registry.sessionCount(PROJECT)).isZero();
            assertThat(registry.snapshot(PROJECT)).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void presenceIsIsolatedBetweenProjects() {
        PresenceRegistry registry = new PresenceRegistry();
        UUID otherProject = new UUID(0L, 2L);
        UUID session = new UUID(0L, 701L);
        UUID user = new UUID(0L, 700L);
        registry.join(PROJECT, session, user, "Ana");

        assertThat(registry.snapshot(PROJECT)).hasSize(1);
        assertThat(registry.snapshot(otherProject)).isEmpty();
        assertThat(registry.sessionCount(PROJECT)).isEqualTo(1);
        assertThat(registry.sessionCount(otherProject)).isZero();
    }

    private static PresenceEntry entryFor(List<PresenceEntry> snapshot, UUID userId) {
        return snapshot.stream()
                .filter(entry -> entry.userId().equals(userId))
                .findFirst()
                .orElseThrow();
    }
}
