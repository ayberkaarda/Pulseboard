package io.pulseboard.core.domain.anomaly;

import java.time.Instant;
import java.util.UUID;

/**
 * Optional filters of an anomaly listing; a {@code null} member does not filter.
 *
 * @param from inclusive lower bound of the window start
 * @param to exclusive upper bound of the window start
 * @param acknowledged {@code true} for acknowledged, {@code false} for open anomalies
 */
public record AnomalyQuery(
        UUID metricId,
        UUID serviceId,
        Severity severity,
        Boolean acknowledged,
        Instant from,
        Instant to) {
}
