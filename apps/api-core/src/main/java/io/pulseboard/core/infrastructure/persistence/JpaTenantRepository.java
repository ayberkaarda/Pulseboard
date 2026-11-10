package io.pulseboard.core.infrastructure.persistence;

import io.pulseboard.core.domain.tenancy.Plan;
import io.pulseboard.core.domain.tenancy.Tenant;
import io.pulseboard.core.domain.tenancy.TenantRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Spring Data implementation of {@link TenantRepository}; queries are derived from the port's method names. */
interface JpaTenantRepository extends TenantRepository, Repository<Tenant, UUID> {

    @Override
    @Query("select t.plan from Tenant t where t.id = :id")
    Optional<Plan> findPlanById(@Param("id") UUID id);
}
