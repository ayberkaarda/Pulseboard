package io.pulseboard.core.application.catalog;

import io.pulseboard.core.application.common.ResourceConflictException;
import io.pulseboard.core.application.common.ResourceNotFoundException;
import io.pulseboard.core.domain.catalog.Aggregation;
import io.pulseboard.core.domain.catalog.Environment;
import io.pulseboard.core.domain.catalog.MetricDefinition;
import io.pulseboard.core.domain.catalog.MetricDefinitionRepository;
import io.pulseboard.core.domain.catalog.Service;
import io.pulseboard.core.domain.catalog.ServiceRepository;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Use cases for the tenant's service catalogue and the metric definitions of each service. */
@Component
public class ServiceCatalogService {

    private final ServiceRepository serviceRepository;
    private final MetricDefinitionRepository metricDefinitionRepository;
    private final Clock clock;

    public ServiceCatalogService(ServiceRepository serviceRepository,
            MetricDefinitionRepository metricDefinitionRepository, Clock clock) {
        this.serviceRepository = serviceRepository;
        this.metricDefinitionRepository = metricDefinitionRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Service> listServices(TenantId tenantId) {
        return serviceRepository.findAllByTenantIdOrderByNameAsc(tenantId);
    }

    @Transactional(readOnly = true)
    public Service getService(TenantId tenantId, UUID serviceId) {
        return requireService(tenantId, serviceId);
    }

    @Transactional
    public Service createService(TenantId tenantId, String name, Environment environment) {
        String trimmedName = name.trim();
        if (serviceRepository.existsByTenantIdAndName(tenantId, trimmedName)) {
            throw new ResourceConflictException("A service named '" + trimmedName + "' already exists");
        }
        return serviceRepository.save(new Service(tenantId, trimmedName, environment, clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<MetricDefinition> listMetrics(TenantId tenantId, UUID serviceId) {
        Service service = requireService(tenantId, serviceId);
        return metricDefinitionRepository.findAllByTenantIdAndServiceIdOrderByNameAsc(tenantId, service.getId());
    }

    @Transactional
    public MetricDefinition createMetric(TenantId tenantId, UUID serviceId, String name, String unit,
            Aggregation aggregation) {
        Service service = requireService(tenantId, serviceId);
        if (metricDefinitionRepository.existsByTenantIdAndServiceIdAndName(tenantId, service.getId(), name)) {
            throw new ResourceConflictException("Service already defines a metric named '" + name + "'");
        }
        return metricDefinitionRepository.save(
                new MetricDefinition(service, name, unit.trim(), aggregation, clock.instant()));
    }

    private Service requireService(TenantId tenantId, UUID serviceId) {
        return serviceRepository.findByIdAndTenantId(serviceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Service", serviceId));
    }
}
