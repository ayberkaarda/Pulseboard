package io.pulseboard.core.infrastructure.persistence;

import io.pulseboard.core.domain.catalog.MetricDefinition;
import io.pulseboard.core.domain.catalog.MetricDefinitionRepository;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Spring Data implementation of {@link MetricDefinitionRepository}; queries are derived from the port's method
 * names.
 */
interface JpaMetricDefinitionRepository extends MetricDefinitionRepository, Repository<MetricDefinition, UUID> {
}
