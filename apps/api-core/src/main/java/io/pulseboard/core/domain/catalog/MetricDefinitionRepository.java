package io.pulseboard.core.domain.catalog;

import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.List;
import java.util.UUID;

/** Persistence port for {@link MetricDefinition}; every query is scoped to a tenant. */
public interface MetricDefinitionRepository {

    MetricDefinition save(MetricDefinition metricDefinition);

    List<MetricDefinition> findAllByTenantIdAndServiceIdOrderByNameAsc(TenantId tenantId, UUID serviceId);

    boolean existsByTenantIdAndServiceIdAndName(TenantId tenantId, UUID serviceId, String name);
}
