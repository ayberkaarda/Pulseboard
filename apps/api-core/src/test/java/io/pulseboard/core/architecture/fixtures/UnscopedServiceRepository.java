package io.pulseboard.core.architecture.fixtures;

import io.pulseboard.core.domain.catalog.Service;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.Optional;
import java.util.UUID;

/** Deliberately broken repository used to prove that the tenant-scoping rule detects unscoped queries. */
public interface UnscopedServiceRepository {

    Optional<Service> findById(UUID id);

    Optional<Service> findByIdAndTenantId(UUID id, TenantId tenantId);
}
