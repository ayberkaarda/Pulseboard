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

    void markSent(UUID eventId, TenantId tenantId, Instant at);

    /** Gives up on the event; {@code error} is stored as given and must already be sanitised. */
    void markFailed(UUID eventId, TenantId tenantId, String error);

    /** Keeps the event PENDING, due again at {@code next}. */
    void reschedule(UUID eventId, TenantId tenantId, Instant next, String error);

    /** Puts a claimed event back without counting the attempt, due again at {@code next}. */
    void defer(UUID eventId, TenantId tenantId, Instant next);
}
