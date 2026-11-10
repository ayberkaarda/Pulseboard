package io.pulseboard.core.domain.tenancy;

import java.util.Optional;

/** Persistence port for {@link Tenant}. Tenants are the scope itself, so lookups are not tenant-filtered. */
public interface TenantRepository {

    Tenant save(Tenant tenant);

    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
