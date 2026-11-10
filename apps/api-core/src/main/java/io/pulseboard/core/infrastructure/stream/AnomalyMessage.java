package io.pulseboard.core.infrastructure.stream;

import io.pulseboard.core.domain.anomaly.Anomaly;
import io.pulseboard.core.domain.anomaly.Detector;
import io.pulseboard.core.domain.anomaly.Severity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Message published to {@code anomalies:{tenantId}} and sent as the data of a server-sent event. It has the same
 * members as the REST anomaly representation; a test keeps the two identical.
 */
record AnomalyMessage(
        UUID id,
        UUID tenantId,
        UUID metricId,
        UUID serviceId,
        Instant windowStart,
        Instant windowEnd,
        double score,
        Detector detector,
        Severity severity,
        Map<String, Object> details,
        Instant createdAt,
        UUID acknowledgedBy,
        Instant acknowledgedAt) {

    static AnomalyMessage of(Anomaly anomaly) {
        return new AnomalyMessage(anomaly.id(), anomaly.tenantId().value(), anomaly.metricId(), anomaly.serviceId(),
                anomaly.windowStart(), anomaly.windowEnd(), anomaly.score(), anomaly.detector(), anomaly.severity(),
                anomaly.details(), anomaly.createdAt(), anomaly.acknowledgedBy(), anomaly.acknowledgedAt());
    }
}
