package io.pulseboard.core.domain.alert;

import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The delivery side of alert events. It works across tenants (a dispatcher serves all of them), so it is not a
 * tenant-scoped repository; every state change still names the tenant of the event and is applied to that pair only.
 */
public interface AlertOutbox {

    /**
     * Claims up to {@code limit} PENDING events that are due and not leased by another dispatcher, oldest first.
     * Each claim counts as an attempt and leases the event until {@code leaseUntil}; a dispatcher that dies
     * mid-delivery therefore loses its lease and another one picks the event up. Rows locked by a concurrent claim
     * are skipped, so several dispatchers claim disjoint events. Events that already used {@code maxAttempts}
     * attempts but are still PENDING (their dispatcher died on the last attempt) are marked FAILED instead.
     */
    List<DueAlert> claimDue(Instant now, Instant leaseUntil, int limit, int maxAttempts);

    /**
     * Records the result of a delivery. Every result method applies only while the caller still owns the claim: the
     * event must be PENDING and leased until exactly {@code claimedUntil}, the value {@link DueAlert#claimedUntil()}
     * carried out of {@link #claimDue}. A worker whose lease ran out and whose event was claimed again therefore
     * changes nothing.
     *
     * @return whether the result was applied; {@code false} when the claim was superseded or the event is final
     */
    boolean markSent(UUID eventId, TenantId tenantId, Instant claimedUntil, Instant at);

    /** Gives up on the event; {@code error} is stored as given and must already be sanitised. */
    boolean markFailed(UUID eventId, TenantId tenantId, Instant claimedUntil, String error);

    /** Keeps the event PENDING, due again at {@code next}. */
    boolean reschedule(UUID eventId, TenantId tenantId, Instant claimedUntil, Instant next, String error);

    /** Puts a claimed event back without counting the attempt, due again at {@code next}. */
    boolean defer(UUID eventId, TenantId tenantId, Instant claimedUntil, Instant next);
}
