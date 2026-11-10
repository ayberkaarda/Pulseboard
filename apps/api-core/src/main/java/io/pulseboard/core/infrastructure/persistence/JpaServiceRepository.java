package io.pulseboard.core.infrastructure.persistence;

import io.pulseboard.core.domain.catalog.Service;
import io.pulseboard.core.domain.catalog.ServiceRepository;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Spring Data implementation of {@link ServiceRepository}; queries are derived from the port's method names. */
interface JpaServiceRepository extends ServiceRepository, Repository<Service, UUID> {
}
