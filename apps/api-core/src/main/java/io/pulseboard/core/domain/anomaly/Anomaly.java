package io.pulseboard.core.domain.anomaly;

import io.pulseboard.core.domain.shared.TenantScoped;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Read model of an anomaly window found by a detector. Rows are written by the analytics service; api-core only
 * reads them and records who acknowledged them.
 *
 * @param serviceId service that owns the metric
 * @param metricName name of the metric, joined in by the repository
 * @param serviceName name of the owning service, joined in by the repository
 * @param details detector specific JSON object, never {@code null}
 * @param acknowledgedBy user that acknowledged the anomaly; {@code null} when it was not acknowledged yet or the
 *     user has been deleted since
 * @param acknowledgedAt when the anomaly was acknowledged; {@code null} while it is open
 */
@TenantScoped
public record Anomaly(
        UUID id,
        TenantId tenantId,
        UUID metricId,
        UUID serviceId,
        String metricName,
        String serviceName,
        Instant windowStart,
        Instant windowEnd,
        double score,
        Detector detector,
        Severity severity,
        Map<String, Object> details,
        Instant createdAt,
        UUID acknowledgedBy,
        Instant acknowledgedAt) {

    public Anomaly {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(metricId, "metricId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(metricName, "metricName");
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        Objects.requireNonNull(detector, "detector");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(createdAt, "createdAt");
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public boolean isAcknowledged() {
        return acknowledgedAt != null;
    }
}
