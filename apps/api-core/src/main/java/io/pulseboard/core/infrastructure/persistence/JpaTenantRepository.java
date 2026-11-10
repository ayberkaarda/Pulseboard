package io.pulseboard.core.infrastructure.persistence;

import io.pulseboard.core.domain.tenancy.Tenant;
import io.pulseboard.core.domain.tenancy.TenantRepository;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Spring Data implementation of {@link TenantRepository}; queries are derived from the port's method names. */
interface JpaTenantRepository extends TenantRepository, Repository<Tenant, UUID> {
}
