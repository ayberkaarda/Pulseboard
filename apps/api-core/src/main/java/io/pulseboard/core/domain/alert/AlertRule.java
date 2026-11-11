package io.pulseboard.core.domain.alert;

import io.pulseboard.core.domain.anomaly.Severity;
import io.pulseboard.core.domain.shared.TenantScoped;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An alert rule of a tenant, with the names of its metric and service joined in by the repository.
 *
 * @param operator comparison for THRESHOLD rules, otherwise {@code null}
 * @param threshold value for THRESHOLD rules, otherwise {@code null}
 * @param minSeverity lowest severity for ANOMALY_SEVERITY rules, otherwise {@code null}
 * @param target webhook URL or e-mail label; sensitive, never part of {@link #toString()}
 * @param createdBy creating user; {@code null} once that user was deleted
 * @param version optimistic-concurrency counter, incremented by every change
 * @param lastTriggeredAt time of the newest alert event of this rule, {@code null} if it never fired
 */
@TenantScoped
public record AlertRule(
        UUID id,
        TenantId tenantId,
        UUID metricId,
        String metricName,
        String metricUnit,
        UUID serviceId,
        String serviceName,
        String name,
        AlertCondition condition,
        ComparisonOperator operator,
        Double threshold,
        Severity minSeverity,
        int cooldownSeconds,
        AlertChannel channel,
        String target,
        boolean enabled,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        long version,
        Instant lastTriggeredAt) {

    public AlertRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(metricId, "metricId");
        Objects.requireNonNull(metricName, "metricName");
        Objects.requireNonNull(metricUnit, "metricUnit");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    @Override
    public String toString() {
        return "AlertRule[id=" + id + ", tenantId=" + tenantId + ", name=" + name + ", condition=" + condition
                + ", channel=" + channel + ", enabled=" + enabled + "]";
    }
}
