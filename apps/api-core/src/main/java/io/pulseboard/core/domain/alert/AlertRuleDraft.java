package io.pulseboard.core.domain.alert;

import io.pulseboard.core.domain.anomaly.Severity;
import java.util.UUID;

/**
 * The editable part of an alert rule, already validated by the application layer.
 *
 * @param operator comparison for THRESHOLD rules, otherwise {@code null}
 * @param threshold value for THRESHOLD rules, otherwise {@code null}
 * @param minSeverity lowest severity for ANOMALY_SEVERITY rules, otherwise {@code null}
 */
public record AlertRuleDraft(
        UUID metricId,
        String name,
        AlertCondition condition,
        ComparisonOperator operator,
        Double threshold,
        Severity minSeverity,
        int cooldownSeconds,
        AlertChannel channel,
        String target,
        boolean enabled) {

    /** Hides the target, which can be a URL with a secret in it. */
    @Override
    public String toString() {
        return "AlertRuleDraft[name=" + name + ", condition=" + condition + ", channel=" + channel + "]";
    }
}
