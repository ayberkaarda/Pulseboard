package io.pulseboard.core.application.alert;

import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import java.time.Duration;
import java.util.Objects;

/**
 * Limits of rule management and rule evaluation.
 *
 * @param maxRulesPerTenant rules one tenant may own
 * @param maxRulesPerMetric rules of one metric that are evaluated per batch (the oldest ones win)
 * @param maxRulesPerBatch rules that are evaluated for one ingestion batch, over all its metrics
 * @param ruleCacheTtl how long the enabled rules of a metric are reused between ingestion batches
 * @param maxPointAge points older than this never raise a threshold alert (back-filled history is not news)
 * @param maxPointFuture points further ahead than this never raise a threshold alert
 * @param eventBudget alert events one tenant may create; over the budget events are dropped
 */
public record AlertPolicy(
        int maxRulesPerTenant,
        int maxRulesPerMetric,
        int maxRulesPerBatch,
        Duration ruleCacheTtl,
        Duration maxPointAge,
        Duration maxPointFuture,
        RateLimitPolicy eventBudget) {

    public AlertPolicy {
        if (maxRulesPerTenant < 1 || maxRulesPerMetric < 1 || maxRulesPerBatch < 1) {
            throw new IllegalArgumentException("rule limits must be positive");
        }
        Objects.requireNonNull(ruleCacheTtl, "ruleCacheTtl");
        Objects.requireNonNull(maxPointAge, "maxPointAge");
        Objects.requireNonNull(maxPointFuture, "maxPointFuture");
        Objects.requireNonNull(eventBudget, "eventBudget");
    }
}
