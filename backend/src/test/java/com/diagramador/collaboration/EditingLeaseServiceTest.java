package com.diagramador.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.diagramador.api.ApiException;
import com.diagramador.domain.TargetType;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class EditingLeaseServiceTest {

    private static final UUID MODEL = new UUID(0L, 1L);
    private static final UUID OTHER_MODEL = new UUID(0L, 2L);
    private static final UUID HOLDER = new UUID(0L, 11L);
    private static final UUID OTHER_USER = new UUID(0L, 12L);
    private static final UUID FIRST_TARGET = new UUID(0L, 101L);
    private static final UUID SECOND_TARGET = new UUID(0L, 102L);
    private static final Instant START = Instant.parse("2026-01-02T03:04:05Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    private MutableClock clock;
    private CurrentUserContext currentUserContext;
    private InMemoryEditingLeaseService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START, ZoneOffset.UTC);
        currentUserContext = new CurrentUserContext();
        service = new InMemoryEditingLeaseService(currentUserContext, clock, TTL);
    }

    @Test
    void editingLeaseRequiresItsCoreFieldsAndACompleteTargetPair() {
        assertThatNullPointerException().isThrownBy(() -> new EditingLease(null, HOLDER, "Ana",
                TargetType.CLASSIFIER, FIRST_TARGET, START, START.plus(TTL)));
        assertThatNullPointerException().isThrownBy(() -> new EditingLease(MODEL, null, "Ana",
                TargetType.CLASSIFIER, FIRST_TARGET, START, START.plus(TTL)));
        assertThatNullPointerException().isThrownBy(() -> new EditingLease(MODEL, HOLDER, null,
                TargetType.CLASSIFIER, FIRST_TARGET, START, START.plus(TTL)));
        assertThatNullPointerException().isThrownBy(() -> new EditingLease(MODEL, HOLDER, "Ana",
                TargetType.CLASSIFIER, FIRST_TARGET, null, START.plus(TTL)));
        assertThatNullPointerException().isThrownBy(() -> new EditingLease(MODEL, HOLDER, "Ana",
                TargetType.CLASSIFIER, FIRST_TARGET, START, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new EditingLease(MODEL, HOLDER, "Ana",
                TargetType.CLASSIFIER, null, START, START.plus(TTL)));
        assertThatIllegalArgumentException().isThrownBy(() -> new EditingLease(MODEL, HOLDER, "Ana",
                null, FIRST_TARGET, START, START.plus(TTL)));
        assertThat(new EditingLease(MODEL, HOLDER, "Ana", null, null, START, START.plus(TTL)).targetId())
                .isNull();
    }

    @Test
    void acquireOnFreeModelReturnsLiveLeaseWithConfiguredExpiry() {
        EditingLease lease = service.acquire(MODEL, HOLDER, "Ana", TargetType.CLASSIFIER, FIRST_TARGET);

        assertThat(lease.modelId()).isEqualTo(MODEL);
        assertThat(lease.editorUserId()).isEqualTo(HOLDER);
        assertThat(lease.editorDisplayName()).isEqualTo("Ana");
        assertThat(lease.targetType()).isEqualTo(TargetType.CLASSIFIER);
        assertThat(lease.targetId()).isEqualTo(FIRST_TARGET);
        assertThat(lease.acquiredAt()).isEqualTo(START);
        assertThat(lease.expiresAt()).isEqualTo(START.plus(TTL));
        assertThat(lease.isLive(clock.instant())).isTrue();
        assertThat(service.current(MODEL)).contains(lease);
    }

    @Test
    void anotherUserCannotAcquireLiveLeaseAndReceivesOrderedConflictDetails() {
        service.acquire(MODEL, HOLDER, "Ana", TargetType.CLASSIFIER, FIRST_TARGET);
        clock.advance(Duration.ofSeconds(7).plusNanos(1));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.acquire(MODEL, OTHER_USER, "Bea", TargetType.OPERATION, SECOND_TARGET));

        assertConflictDetails(exception, "Ana", TargetType.CLASSIFIER, FIRST_TARGET, false, 23L);
    }

    @Test
    void sameUsersAcquireRefreshesTargetAndExpiryWithoutChangingAcquisitionTime() {
        EditingLease original = service.acquire(MODEL, HOLDER, "Ana", TargetType.CLASSIFIER, FIRST_TARGET);
        clock.advance(Duration.ofSeconds(5));

        EditingLease refreshed = service.acquire(MODEL, HOLDER, "Ana M.", TargetType.OPERATION, SECOND_TARGET);

        assertThat(refreshed.editorDisplayName()).isEqualTo("Ana M.");
        assertThat(refreshed.targetType()).isEqualTo(TargetType.OPERATION);
        assertThat(refreshed.targetId()).isEqualTo(SECOND_TARGET);
        assertThat(refreshed.acquiredAt()).isEqualTo(original.acquiredAt());
        assertThat(refreshed.expiresAt()).isEqualTo(clock.instant().plus(TTL)).isAfter(original.expiresAt());
        assertThat(service.current(MODEL)).contains(refreshed);
    }

    @Test
    void renewOnlyExtendsALiveLeaseHeldByTheRequester() {
        EditingLease original = service.acquire(MODEL, HOLDER, "Ana", TargetType.CLASSIFIER, FIRST_TARGET);
        assertThat(service.renew(MODEL, OTHER_USER)).isEmpty();
        assertThat(service.current(MODEL)).contains(original);

        clock.advance(Duration.ofSeconds(4));
        EditingLease renewed = service.renew(MODEL, HOLDER).orElseThrow();
        assertThat(renewed.expiresAt()).isEqualTo(clock.instant().plus(TTL)).isAfter(original.expiresAt());
        assertThat(renewed.acquiredAt()).isEqualTo(original.acquiredAt());
        assertThat(renewed.targetId()).isEqualTo(original.targetId());

        clock.advance(TTL);
        assertThat(renewed.isLive(clock.instant())).isFalse();
        assertThat(service.renew(MODEL, HOLDER)).isEmpty();
        assertThat(service.current(MODEL)).contains(renewed);
    }

    @Test
    void releaseIsHolderOnlyIdempotentAndAllowsANewAcquisition() {
        EditingLease original = service.acquire(MODEL, HOLDER, "Ana", TargetType.CLASSIFIER, FIRST_TARGET);

        service.release(MODEL, OTHER_USER);
        assertThat(service.current(MODEL)).contains(original);

        service.release(MODEL, HOLDER);
        service.release(MODEL, HOLDER);
        assertThat(service.current(MODEL)).isEmpty();
        EditingLease replacement = service.acquire(MODEL, OTHER_USER, "Bea", null, null);
        assertThat(replacement.editorUserId()).isEqualTo(OTHER_USER);
        assertThat(replacement.targetType()).isNull();
        assertThat(replacement.targetId()).isNull();
    }

    @Test
    void expiredLeaseRemainsRecordedButNoLongerBlocksExport() {
        EditingLease lease = service.acquire(MODEL, HOLDER, "Ana", TargetType.CLASSIFIER, FIRST_TARGET);
        clock.advance(TTL.plusNanos(1));

        assertThat(lease.isLive(clock.instant())).isFalse();
        assertThat(service.current(MODEL)).contains(lease);
        currentUserContext.set(user(OTHER_USER, "Bea"));
        assertThatCode(() -> service.requireExportAllowed(MODEL)).doesNotThrowAnyException();
    }

    @Test
    void exportGuardBlocksOnlyLiveForeignLeasesAndExemptsHolderAndOperator() {
        currentUserContext.set(user(OTHER_USER, "Bea"));
        assertThatCode(() -> service.requireExportAllowed(OTHER_MODEL)).doesNotThrowAnyException();

        EditingLease lease = service.acquire(MODEL, HOLDER, "Ana", TargetType.CLASSIFIER, FIRST_TARGET);
        ApiException blocked = assertThrows(ApiException.class, () -> service.requireExportAllowed(MODEL));
        assertConflictDetails(blocked, "Ana", TargetType.CLASSIFIER, FIRST_TARGET, false, 30L);

        currentUserContext.set(user(HOLDER, "Ana"));
        assertThatCode(() -> service.requireExportAllowed(MODEL)).doesNotThrowAnyException();

        currentUserContext.setOperator();
        assertThatCode(() -> service.requireExportAllowed(MODEL)).doesNotThrowAnyException();

        currentUserContext.clear();
        ApiException anonymousBlocked = assertThrows(ApiException.class,
                () -> service.requireExportAllowed(MODEL));
        assertConflictDetails(anonymousBlocked, "Ana", TargetType.CLASSIFIER, FIRST_TARGET, false, 30L);
        assertThat(service.current(MODEL)).contains(lease);
    }

    @Test
    void acquireAgainstExpiredForeignLeaseRequiresExplicitReclaim() {
        service.acquire(MODEL, HOLDER, "Ana", TargetType.CLASSIFIER, FIRST_TARGET);
        clock.advance(TTL);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.acquire(MODEL, OTHER_USER, "Bea", TargetType.OPERATION, SECOND_TARGET));

        assertConflictDetails(exception, "Ana", TargetType.CLASSIFIER, FIRST_TARGET, true, 0L);
    }

    @Test
    void reclaimAfterExpiryReplacesHolderNameAndTarget() {
        service.acquire(MODEL, HOLDER, "Ana", TargetType.CLASSIFIER, FIRST_TARGET);
        clock.advance(TTL);

        EditingLease reclaimed = service.reclaim(MODEL, OTHER_USER, "Bea", TargetType.OPERATION, SECOND_TARGET);

        assertThat(reclaimed.modelId()).isEqualTo(MODEL);
        assertThat(reclaimed.editorUserId()).isEqualTo(OTHER_USER);
        assertThat(reclaimed.editorDisplayName()).isEqualTo("Bea");
        assertThat(reclaimed.targetType()).isEqualTo(TargetType.OPERATION);
        assertThat(reclaimed.targetId()).isEqualTo(SECOND_TARGET);
        assertThat(reclaimed.acquiredAt()).isEqualTo(clock.instant());
        assertThat(reclaimed.expiresAt()).isEqualTo(clock.instant().plus(TTL));
        assertThat(service.current(MODEL)).contains(reclaimed);
    }

    @Test
    void reclaimWhileLeaseIsLiveThrowsAndLeavesOriginalLeaseUntouched() {
        EditingLease original = service.acquire(MODEL, HOLDER, "Ana", TargetType.CLASSIFIER, FIRST_TARGET);
        clock.advance(Duration.ofSeconds(5));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.reclaim(MODEL, OTHER_USER, "Bea", TargetType.OPERATION, SECOND_TARGET));

        assertConflictDetails(exception, "Ana", TargetType.CLASSIFIER, FIRST_TARGET, false, 25L);
        assertThat(service.current(MODEL)).contains(original);
        assertThat(service.current(MODEL).orElseThrow().expiresAt()).isEqualTo(START.plus(TTL));
    }

    @Test
    void reclaimOnFreeModelWorks() {
        EditingLease reclaimed = service.reclaim(MODEL, OTHER_USER, "Bea", null, null);

        assertThat(reclaimed.editorUserId()).isEqualTo(OTHER_USER);
        assertThat(reclaimed.editorDisplayName()).isEqualTo("Bea");
        assertThat(reclaimed.targetType()).isNull();
        assertThat(reclaimed.targetId()).isNull();
        assertThat(service.current(MODEL)).contains(reclaimed);
    }

    @Test
    void partialTargetPairsAreRejectedWithoutChangingTheLease() {
        EditingLease original = service.acquire(MODEL, HOLDER, "Ana", TargetType.CLASSIFIER, FIRST_TARGET);

        ApiException missingId = assertThrows(ApiException.class,
                () -> service.acquire(MODEL, OTHER_USER, "Bea", TargetType.OPERATION, null));
        assertInvalidPayload(missingId);
        ApiException missingType = assertThrows(ApiException.class,
                () -> service.reclaim(MODEL, OTHER_USER, "Bea", null, SECOND_TARGET));
        assertInvalidPayload(missingType);
        assertThat(service.current(MODEL)).contains(original);
    }

    @Test
    void concurrentDistinctUsersCannotBothAcquireTheSameFreeModel() throws Exception {
        int workerCount = 8;
        CyclicBarrier barrier = new CyclicBarrier(workerCount);
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        List<Future<AcquisitionAttempt>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < workerCount; i++) {
                UUID userId = new UUID(1L, i + 1L);
                String displayName = "User " + i;
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        service.acquire(MODEL, userId, displayName, TargetType.CLASSIFIER, FIRST_TARGET);
                        return new AcquisitionAttempt(userId, true);
                    } catch (ApiException exception) {
                        assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(exception.code()).isEqualTo("EXPORT_BLOCKED_EDITING");
                        return new AcquisitionAttempt(userId, false);
                    }
                }));
            }

            List<AcquisitionAttempt> attempts = new ArrayList<>();
            for (Future<AcquisitionAttempt> future : futures) {
                attempts.add(future.get(10, TimeUnit.SECONDS));
            }
            List<AcquisitionAttempt> winners = attempts.stream().filter(AcquisitionAttempt::acquired).toList();
            List<AcquisitionAttempt> conflicts = attempts.stream().filter(attempt -> !attempt.acquired()).toList();
            assertThat(winners).hasSize(1);
            assertThat(conflicts).hasSize(7);
            assertThat(service.current(MODEL).orElseThrow().editorUserId()).isEqualTo(winners.get(0).userId());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertConflictDetails(ApiException exception, String displayName, TargetType targetType,
            UUID targetId, boolean reclaimable, long retryAfterSeconds) {
        assertThat(exception.status().value()).isEqualTo(409);
        assertThat(exception.code()).isEqualTo("EXPORT_BLOCKED_EDITING");
        assertThat(exception.details()).hasSize(1);
        var details = exception.details().get(0);
        assertThat(details).isInstanceOf(LinkedHashMap.class);
        assertThat(new ArrayList<>(details.keySet())).containsExactly(
                "editorDisplayName", "targetType", "targetId", "reclaimable", "retryAfterSeconds");
        assertThat(details.get("editorDisplayName")).isEqualTo(displayName);
        assertThat(details.get("targetType")).isEqualTo(targetType == null ? null : targetType.name());
        assertThat(details.get("targetId")).isEqualTo(targetId);
        assertThat(details.get("reclaimable")).isEqualTo(reclaimable);
        assertThat(details.get("retryAfterSeconds")).isEqualTo(retryAfterSeconds);
    }

    private static void assertInvalidPayload(ApiException exception) {
        assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.code()).isEqualTo("INVALID_PAYLOAD");
        assertThat(exception.details()).isEmpty();
    }

    private static CurrentUser user(UUID userId, String displayName) {
        return new CurrentUser(userId, displayName.toLowerCase(), displayName);
    }

    private record AcquisitionAttempt(UUID userId, boolean acquired) {
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = new AtomicReference<>(instant);
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }

        private void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }
    }
}
