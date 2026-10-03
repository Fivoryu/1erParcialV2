package com.diagramador.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EditingLeaseConflictTest {

    private static final UUID MODEL = new UUID(0L, 1L);
    private static final UUID HOLDER = new UUID(0L, 11L);
    private static final UUID REQUESTER = new UUID(0L, 12L);
    private static final UUID TARGET = new UUID(0L, 101L);
    private static final Instant START = Instant.parse("2026-01-02T03:04:05Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    private MutableClock clock;
    private CurrentUserContext currentUserContext;
    private InMemoryEditingLeaseService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        currentUserContext = new CurrentUserContext();
        currentUserContext.set(user(REQUESTER, "Bea"));
        service = new InMemoryEditingLeaseService(currentUserContext, clock, TTL);
    }

    @Test
    void blockerIsEmptyOnlyWhenNoLeaseHasBeenRecorded() {
        assertThat(service.blocker(MODEL)).isEmpty();

        service.acquire(MODEL, HOLDER, "Ana", TargetType.CLASSIFIER, TARGET);
        assertThat(service.blocker(MODEL)).isPresent();

        clock.advance(TTL);
        assertThat(service.blocker(MODEL)).isPresent();
    }

    @Test
    void liveForeignBlockerHasOrderedKeysAndMatchesExportRejectionDetails() {
        service.acquire(MODEL, HOLDER, "Ana", TargetType.CLASSIFIER, TARGET);
        clock.advance(Duration.ofSeconds(7).plusNanos(1));

        EditingLeaseConflict conflict = service.blocker(MODEL).orElseThrow();
        assertThat(conflict.editorDisplayName()).isEqualTo("Ana");
        assertThat(conflict.targetType()).isEqualTo(TargetType.CLASSIFIER);
        assertThat(conflict.targetId()).isEqualTo(TARGET);
        assertThat(conflict.reclaimable()).isFalse();
        assertThat(conflict.retryAfterSeconds()).isEqualTo(23);

        ApiException exception = assertThrows(ApiException.class, () -> service.requireExportAllowed(MODEL));
        assertConflictDetailsMatch(conflict, exception);
        assertThat(EditingLeaseConflict.fromDetails(conflict.toDetails())).isEqualTo(conflict);
    }

    @Test
    void expiredForeignBlockerIsReclaimableAndHasNoRetryDelay() {
        service.acquire(MODEL, HOLDER, "Ana", TargetType.CLASSIFIER, TARGET);
        clock.advance(TTL);

        EditingLeaseConflict conflict = service.blocker(MODEL).orElseThrow();
        assertThat(conflict.editorDisplayName()).isEqualTo("Ana");
        assertThat(conflict.targetType()).isEqualTo(TargetType.CLASSIFIER);
        assertThat(conflict.targetId()).isEqualTo(TARGET);
        assertThat(conflict.reclaimable()).isTrue();
        assertThat(conflict.retryAfterSeconds()).isZero();

        ApiException exception = assertThrows(ApiException.class,
                () -> service.acquire(MODEL, REQUESTER, "Bea", TargetType.OPERATION, UUID.randomUUID()));
        assertConflictDetailsMatch(conflict, exception);
    }

    private static void assertConflictDetailsMatch(EditingLeaseConflict conflict, ApiException exception) {
        assertThat(exception.details()).hasSize(1);
        var details = exception.details().get(0);
        assertThat(details).isInstanceOf(LinkedHashMap.class);
        assertThat(new ArrayList<>(details.keySet())).containsExactly(
                "editorDisplayName", "targetType", "targetId", "reclaimable", "retryAfterSeconds");
        assertThat(details.get("editorDisplayName")).isEqualTo(conflict.editorDisplayName());
        assertThat(details.get("targetType")).isEqualTo(conflict.targetType().name());
        assertThat(details.get("targetId")).isEqualTo(conflict.targetId());
        assertThat(details.get("reclaimable")).isEqualTo(conflict.reclaimable());
        assertThat(details.get("retryAfterSeconds")).isEqualTo((long) conflict.retryAfterSeconds());
    }

    private static CurrentUser user(UUID userId, String displayName) {
        return new CurrentUser(userId, displayName.toLowerCase(), displayName);
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;

        private MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
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
