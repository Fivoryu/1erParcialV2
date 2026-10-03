package com.diagramador.collaboration;

import com.diagramador.api.ApiException;
import com.diagramador.domain.TargetType;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * In-memory, per-model editing leases for one application instance; there is no persistence and no event
 * publication, so leases do not cross instances. The TTL is refreshed by the editor heartbeat. An editor
 * whose heartbeat keeps flowing cannot be dispossessed: explicit reclamation succeeds only after expiry.
 */
@Component
public class InMemoryEditingLeaseService implements EditingLeaseService {

    private final ConcurrentHashMap<UUID, EditingLease> leases = new ConcurrentHashMap<>();
    private final CurrentUserContext currentUserContext;
    private final Clock clock;
    private final Duration ttl;

    @Autowired
    public InMemoryEditingLeaseService(CurrentUserContext currentUserContext,
            @Value("${diagrammer.collaboration.editing-lease-ttl:PT30S}") Duration ttl) {
        this(currentUserContext, Clock.systemUTC(), ttl);
    }

    InMemoryEditingLeaseService(CurrentUserContext currentUserContext, Clock clock, Duration ttl) {
        this.currentUserContext = Objects.requireNonNull(currentUserContext, "currentUserContext");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive.");
        }
    }

    @Override
    public EditingLease acquire(UUID modelId, UUID editorUserId, String editorDisplayName,
            TargetType targetType, UUID targetId) {
        requireModelAndEditor(modelId, editorUserId);
        Objects.requireNonNull(editorDisplayName, "editorDisplayName");
        validateTargetPair(targetType, targetId);

        return leases.compute(modelId, (id, existing) -> {
            Instant now = clock.instant();
            if (existing == null) {
                return newLease(modelId, editorUserId, editorDisplayName, targetType, targetId, now);
            }
            if (existing.editorUserId().equals(editorUserId)) {
                return new EditingLease(modelId, editorUserId, editorDisplayName, targetType, targetId,
                        existing.acquiredAt(), now.plus(ttl));
            }
            throw blocked(blocker(modelId).orElseThrow(() ->
                    new IllegalStateException("Conflicting lease disappeared during acquisition.")));
        });
    }

    @Override
    public Optional<EditingLease> renew(UUID modelId, UUID editorUserId) {
        requireModelAndEditor(modelId, editorUserId);
        AtomicReference<EditingLease> renewed = new AtomicReference<>();
        leases.computeIfPresent(modelId, (id, existing) -> {
            Instant now = clock.instant();
            if (!existing.editorUserId().equals(editorUserId) || !existing.isLive(now)) {
                return existing;
            }
            EditingLease refreshed = new EditingLease(existing.modelId(), existing.editorUserId(),
                    existing.editorDisplayName(), existing.targetType(), existing.targetId(),
                    existing.acquiredAt(), now.plus(ttl));
            renewed.set(refreshed);
            return refreshed;
        });
        return Optional.ofNullable(renewed.get());
    }

    @Override
    public void release(UUID modelId, UUID editorUserId) {
        requireModelAndEditor(modelId, editorUserId);
        leases.computeIfPresent(modelId,
                (id, existing) -> existing.editorUserId().equals(editorUserId) ? null : existing);
    }

    @Override
    public EditingLease reclaim(UUID modelId, UUID editorUserId, String editorDisplayName,
            TargetType targetType, UUID targetId) {
        requireModelAndEditor(modelId, editorUserId);
        Objects.requireNonNull(editorDisplayName, "editorDisplayName");
        validateTargetPair(targetType, targetId);

        return leases.compute(modelId, (id, existing) -> {
            Instant now = clock.instant();
            if (existing != null && existing.isLive(now)) {
                throw blocked(blocker(modelId).orElseThrow(() ->
                        new IllegalStateException("Conflicting lease disappeared during reclamation.")));
            }
            return newLease(modelId, editorUserId, editorDisplayName, targetType, targetId, now);
        });
    }

    @Override
    public Optional<EditingLease> current(UUID modelId) {
        Objects.requireNonNull(modelId, "modelId");
        return Optional.ofNullable(leases.get(modelId));
    }

    @Override
    public Optional<EditingLeaseConflict> blocker(UUID modelId) {
        Objects.requireNonNull(modelId, "modelId");
        EditingLease existing = leases.get(modelId);
        if (existing == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        boolean live = existing.isLive(now);
        int retryAfterSeconds = live ? retryAfterSeconds(now, existing.expiresAt()) : 0;
        return Optional.of(new EditingLeaseConflict(existing.editorDisplayName(), existing.targetType(),
                existing.targetId(), !live, retryAfterSeconds));
    }

    @Override
    public void requireExportAllowed(UUID modelId) {
        Objects.requireNonNull(modelId, "modelId");
        if (currentUserContext.isOperator()) {
            return;
        }
        UUID requesterUserId = currentUserContext.optional().map(CurrentUser::userId).orElse(null);
        Optional<EditingLeaseConflict> blocker = blocker(modelId);
        if (blocker.isEmpty() || blocker.get().reclaimable()) {
            return;
        }
        EditingLease existing = leases.get(modelId);
        if (existing != null && !existing.editorUserId().equals(requesterUserId)) {
            throw blocked(blocker.get());
        }
    }

    private EditingLease newLease(UUID modelId, UUID editorUserId, String editorDisplayName,
            TargetType targetType, UUID targetId, Instant now) {
        return new EditingLease(modelId, editorUserId, editorDisplayName, targetType, targetId,
                now, now.plus(ttl));
    }

    private static void requireModelAndEditor(UUID modelId, UUID editorUserId) {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(editorUserId, "editorUserId");
    }

    private static void validateTargetPair(TargetType targetType, UUID targetId) {
        if ((targetType == null) != (targetId == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD",
                    "Target type and ID must be provided together.", List.of(), null);
        }
    }

    private static ApiException blocked(EditingLeaseConflict conflict) {
        return new ApiException(HttpStatus.CONFLICT, "EXPORT_BLOCKED_EDITING",
                "Export is blocked by an editing lease.", List.of(conflict.toDetails()), null);
    }

    private static int retryAfterSeconds(Instant now, Instant expiresAt) {
        Duration remaining = Duration.between(now, expiresAt);
        if (remaining.isZero() || remaining.isNegative()) {
            return 0;
        }
        long seconds = remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1);
        return (int) Math.min(Integer.MAX_VALUE, seconds);
    }
}
