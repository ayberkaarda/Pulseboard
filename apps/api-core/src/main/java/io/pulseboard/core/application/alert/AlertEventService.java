package io.pulseboard.core.application.alert;

import io.pulseboard.core.application.common.InvalidRequestException;
import io.pulseboard.core.application.common.InvalidRequestException.Violation;
import io.pulseboard.core.application.common.ResourceNotFoundException;
import io.pulseboard.core.application.query.MetricQueryService;
import io.pulseboard.core.domain.alert.AlertEvent;
import io.pulseboard.core.domain.alert.AlertEventPage;
import io.pulseboard.core.domain.alert.AlertEventQuery;
import io.pulseboard.core.domain.alert.AlertEventRepository;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Use cases for reading the alert history of a tenant. */
@Service
public class AlertEventService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    /** Deep paging is not a use case; the limit also keeps {@code page * size} far from overflowing. */
    public static final int MAX_PAGE = 100_000;

    private final AlertEventRepository eventRepository;

    public AlertEventService(AlertEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public AlertEventPage list(TenantId tenantId, AlertEventQuery query, int page, int size) {
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
        // Storage has microsecond precision: [from, to) on nanosecond bounds equals [ceil(from), ceil(to)).
        AlertEventQuery normalised = new AlertEventQuery(query.ruleId(), query.status(), ceil(query.from()),
                ceil(query.to()));
        return eventRepository.search(tenantId, normalised, page, size);
    }

    public AlertEvent get(TenantId tenantId, UUID id) {
        return eventRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Alert event", id));
    }

    private static Instant ceil(Instant bound) {
        return bound == null ? null : MetricQueryService.ceilToMicros(bound);
    }
}
