package io.pulseboard.core.domain.catalog;

import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link Service}; every query is scoped to a tenant. */
public interface ServiceRepository {

    Service save(Service service);

    Optional<Service> findByIdAndTenantId(UUID id, TenantId tenantId);

    List<Service> findAllByTenantIdOrderByNameAsc(TenantId tenantId);

    boolean existsByTenantIdAndName(TenantId tenantId, String name);
}
