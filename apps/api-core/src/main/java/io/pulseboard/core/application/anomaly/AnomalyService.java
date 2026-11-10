package io.pulseboard.core.application.anomaly;

import io.pulseboard.core.application.common.InvalidRequestException;
import io.pulseboard.core.application.common.InvalidRequestException.Violation;
import io.pulseboard.core.application.common.ResourceNotFoundException;
import io.pulseboard.core.domain.anomaly.Anomaly;
import io.pulseboard.core.domain.anomaly.AnomalyPage;
import io.pulseboard.core.domain.anomaly.AnomalyQuery;
import io.pulseboard.core.domain.anomaly.AnomalyRepository;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Use cases for reading and acknowledging the anomalies of a tenant. */
@Component
public class AnomalyService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    /** Deep paging is not a use case; the limit also keeps {@code page * size} far from overflowing. */
    public static final int MAX_PAGE = 100_000;

    private final AnomalyRepository anomalyRepository;
    private final Clock clock;

    public AnomalyService(AnomalyRepository anomalyRepository, Clock clock) {
        this.anomalyRepository = anomalyRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AnomalyPage list(TenantId tenantId, AnomalyQuery query, int page, int size) {
        List<Violation> violations = new ArrayList<>();
        if (page < 0 || page > MAX_PAGE) {
            violations.add(new Violation("page", "must be between 0 and " + MAX_PAGE));
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            violations.add(new Violation("size", "must be between 1 and " + MAX_PAGE_SIZE));
        }
        if (query.from() != null && query.to() != null && !query.from().isBefore(query.to())) {
            violations.add(new Violation("to", "must be after from"));
        }
        if (!violations.isEmpty()) {
            throw new InvalidRequestException("The query parameters are invalid", violations);
        }
        return anomalyRepository.search(tenantId, query, page, size);
    }

    @Transactional(readOnly = true)
    public Anomaly get(TenantId tenantId, UUID id) {
        return anomalyRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Anomaly", id));
    }

    /**
     * Acknowledges an anomaly of the tenant. Idempotent: the first acknowledgement wins and later calls, by the
     * same or another user, return the stored state unchanged. A conditional update decides, so two concurrent
     * calls cannot both record themselves.
     */
    @Transactional
    public Anomaly acknowledge(TenantId tenantId, UUID userId, UUID id) {
        get(tenantId, id);
        anomalyRepository.acknowledge(id, tenantId, userId, clock.instant());
        return get(tenantId, id);
    }
}
