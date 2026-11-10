package io.pulseboard.core.api.catalog;

import io.pulseboard.core.api.catalog.CatalogDtos.MetricDefinitionResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.ServiceResponse;
import io.pulseboard.core.application.catalog.ServiceSummary;
import io.pulseboard.core.domain.catalog.MetricDefinition;
import io.pulseboard.core.domain.catalog.Service;
import java.util.List;
import org.mapstruct.Mapper;

/** Maps catalogue aggregates to response bodies. Tenant identifiers are never exposed. */
@Mapper
public interface CatalogMapper {

    /** A service that was just created has no samples yet. */
    default ServiceResponse toResponse(Service service) {
        return toResponse(new ServiceSummary(service, null));
    }

    default ServiceResponse toResponse(ServiceSummary summary) {
        Service service = summary.service();
        return new ServiceResponse(service.getId(), service.getName(), service.getEnvironment(),
                service.getCreatedAt(), summary.lastPointAt());
    }

    default List<ServiceResponse> toServiceResponses(List<ServiceSummary> summaries) {
        return summaries.stream().map(this::toResponse).toList();
    }

    MetricDefinitionResponse toResponse(MetricDefinition metricDefinition);

    List<MetricDefinitionResponse> toMetricResponses(List<MetricDefinition> metricDefinitions);
}
