package com.diagramador.collaboration;

import com.diagramador.api.ApiException;
import com.diagramador.domain.TargetType;
import java.util.Optional;
import java.util.UUID;

/**
 * Thread-safe contract for the ephemeral editing lease associated with each UML model. The implementation
 * for this increment is in-memory and single-instance: leases do not cross application instances, are not
 * persisted, and publish no events. A live editor whose heartbeat continues cannot be dispossessed; control
 * becomes reclaimable only after the lease expires.
 */
public interface EditingLeaseService {

    /**
     * Takes an unleased model, or refreshes the target and expiry when the supplied user already holds it.
     * Starting to edit never dispossesses another user: a live foreign lease throws
     * {@code ApiException(409, "EXPORT_BLOCKED_EDITING")} with {@code reclaimable: false}; an expired foreign
     * lease throws the same conflict with {@code reclaimable: true} and {@code retryAfterSeconds: 0}.
     * A partial target pair is rejected with {@code ApiException(400, "INVALID_PAYLOAD")} before state changes.
     *
     * @param modelId model whose lease is requested
     * @param editorUserId requesting editor
     * @param editorDisplayName display name to record for the editor
     * @param targetType selected target type, or {@code null} when nothing is selected
     * @param targetId selected target ID, or {@code null} when nothing is selected
     * @return the new or refreshed lease
     */
    EditingLease acquire(UUID modelId, UUID editorUserId, String editorDisplayName,
            TargetType targetType, UUID targetId);

    /**
     * Extends the lease only when the requester is its live holder. A different user or an expired lease
     * returns {@link Optional#empty()}; renewal never revives an expired lease.
     *
     * @param modelId model whose lease is renewed
     * @param editorUserId requesting editor
     * @return the refreshed lease, or empty when this user cannot renew it
     */
    Optional<EditingLease> renew(UUID modelId, UUID editorUserId);

    /** Removes the lease only when the requester is its holder; repeated or non-holder releases do nothing. */
    void release(UUID modelId, UUID editorUserId);

    /**
     * Explicitly takes control of a free or non-live lease. This is the only operation that can replace
     * another user's lease; a live lease throws {@code ApiException(409, "EXPORT_BLOCKED_EDITING")} and is
     * left unchanged. A partial target pair is rejected with {@code ApiException(400, "INVALID_PAYLOAD")}.
     *
     * @param modelId model whose lease is reclaimed
     * @param editorUserId requesting editor
     * @param editorDisplayName display name to record for the new holder
     * @param targetType selected target type, or {@code null} when nothing is selected
     * @param targetId selected target ID, or {@code null} when nothing is selected
     * @return the new lease held by the requester
     */
    EditingLease reclaim(UUID modelId, UUID editorUserId, String editorDisplayName,
            TargetType targetType, UUID targetId);

    /** Returns the recorded lease, whether live or expired, or empty if no lease is recorded. */
    Optional<EditingLease> current(UUID modelId);

    /** Returns conflict details for any recorded lease, whether live or expired. */
    Optional<EditingLeaseConflict> blocker(UUID modelId);

    /**
     * Throws {@code ApiException(409, "EXPORT_BLOCKED_EDITING")} only when another user holds a live lease.
     * The current holder and the operator principal may export; an anonymous requester is treated as a
     * different user. Conflict details contain exactly {@code editorDisplayName}, {@code targetType},
     * {@code targetId}, {@code reclaimable}, and {@code retryAfterSeconds}, in that order. A partial target
     * pair is never stored; the in-memory implementation rejects it at acquisition/reclaim time.
     *
     * @param modelId model being exported
     */
    void requireExportAllowed(UUID modelId);
}
