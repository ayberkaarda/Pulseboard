package io.pulseboard.core.domain.anomaly;

import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link Anomaly}. Every method is scoped to a tenant; an anomaly of another tenant is
 * indistinguishable from one that does not exist. Anomalies are inserted by the analytics service, so there is no
 * insert here.
 */
public interface AnomalyRepository {

    Optional<Anomaly> findByIdAndTenantId(UUID id, TenantId tenantId);

    /** Returns the anomalies of {@code tenantId} among {@code ids}; ids that do not match are simply absent. */
    List<Anomaly> findAllByTenantIdAndIdIn(TenantId tenantId, Collection<UUID> ids);

    /**
     * Lists anomalies newest window first (window start, then id, both descending), so pages never overlap or
     * skip rows while the data does not change.
     *
     * @param page zero-based page number
     */
    AnomalyPage search(TenantId tenantId, AnomalyQuery query, int page, int size);

    /**
     * Records the acknowledgement when the anomaly is still open; an earlier acknowledgement is kept.
     *
     * @return number of rows updated (0 or 1)
     */
    int acknowledge(UUID id, TenantId tenantId, UUID userId, Instant at);
}
