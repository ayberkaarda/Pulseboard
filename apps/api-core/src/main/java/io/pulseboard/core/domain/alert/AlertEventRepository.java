package io.pulseboard.core.domain.alert;

import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link AlertEvent}; every method is scoped to a tenant. */
public interface AlertEventRepository {

    /**
     * Records the event as PENDING and due now. The rule must belong to the tenant.
     *
     * @return {@code false} when an event with the same rule and source reference exists already (nothing is
     *     changed)
     */
    boolean insertIfAbsent(TenantId tenantId, NewAlertEvent event);

    Optional<AlertEvent> findByIdAndTenantId(UUID id, TenantId tenantId);

    /** Lists events newest first (trigger time, then id, both descending), so pages never overlap. */
    AlertEventPage search(TenantId tenantId, AlertEventQuery query, int page, int size);
}
