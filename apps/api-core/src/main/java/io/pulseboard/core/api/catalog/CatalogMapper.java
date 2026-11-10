package io.pulseboard.core.api.catalog;

import io.pulseboard.core.api.catalog.CatalogDtos.MetricDefinitionResponse;
import io.pulseboard.core.api.catalog.CatalogDtos.ServiceResponse;
import io.pulseboard.core.domain.catalog.MetricDefinition;
import io.pulseboard.core.domain.catalog.Service;
import java.util.List;
import org.mapstruct.Mapper;

/** Maps catalogue aggregates to response bodies. Tenant identifiers are never exposed. */
@Mapper
public interface CatalogMapper {

    ServiceResponse toResponse(Service service);

    List<ServiceResponse> toServiceResponses(List<Service> services);

    MetricDefinitionResponse toResponse(MetricDefinition metricDefinition);

    List<MetricDefinitionResponse> toMetricResponses(List<MetricDefinition> metricDefinitions);
}
