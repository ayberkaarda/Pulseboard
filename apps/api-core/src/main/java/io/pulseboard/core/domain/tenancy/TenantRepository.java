package io.pulseboard.core.domain.tenancy;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link Tenant}. Tenants are the scope itself, so lookups are not tenant-filtered. */
public interface TenantRepository {

    Tenant save(Tenant tenant);

    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** The plan of the tenant with the given id, which drives its quotas. */
    Optional<Plan> findPlanById(UUID id);
}
