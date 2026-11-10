package io.pulseboard.core.application.ingest;

import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import io.pulseboard.core.domain.tenancy.Plan;
import java.util.Objects;

/**
 * Ingestion quotas and limits.
 *
 * @param free batches a FREE tenant may send (token bucket)
 * @param pro batches a PRO tenant may send (token bucket)
 * @param maxBodyBytes maximum size of a raw request body
 */
public record IngestionPolicy(RateLimitPolicy free, RateLimitPolicy pro, int maxBodyBytes) {

    public IngestionPolicy {
        Objects.requireNonNull(free, "free");
        Objects.requireNonNull(pro, "pro");
        if (maxBodyBytes < 1024) {
            throw new IllegalArgumentException("maxBodyBytes must be at least 1024");
        }
    }

    public RateLimitPolicy forPlan(Plan plan) {
        return switch (plan) {
            case FREE -> free;
            case PRO -> pro;
        };
    }
}
