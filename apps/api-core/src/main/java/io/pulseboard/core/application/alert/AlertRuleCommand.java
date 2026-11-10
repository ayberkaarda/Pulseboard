package io.pulseboard.core.application.alert;

import io.pulseboard.core.domain.alert.AlertChannel;
import io.pulseboard.core.domain.alert.AlertCondition;
import io.pulseboard.core.domain.alert.ComparisonOperator;
import io.pulseboard.core.domain.anomaly.Severity;
import java.util.UUID;

/**
 * The editable fields of an alert rule as sent by a client; nothing is validated yet.
 *
 * @param enabled {@code null} means enabled
 */
public record AlertRuleCommand(
        UUID metricId,
        String name,
        AlertCondition condition,
        ComparisonOperator operator,
        Double threshold,
        Severity minSeverity,
        Integer cooldownSeconds,
        AlertChannel channel,
        String target,
        Boolean enabled) {

    /** Hides the target, which can be a URL with a secret in it. */
    @Override
    public String toString() {
        return "AlertRuleCommand[name=" + name + ", condition=" + condition + ", channel=" + channel + "]";
    }
}
