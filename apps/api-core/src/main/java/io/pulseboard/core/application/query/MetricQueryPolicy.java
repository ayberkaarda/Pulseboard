package io.pulseboard.core.application.query;

import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import java.util.Objects;

/**
 * Metric query quota.
 *
 * @param perTenant queries a tenant may run (token bucket shared by all its users)
 */
public record MetricQueryPolicy(RateLimitPolicy perTenant) {

    public MetricQueryPolicy {
        Objects.requireNonNull(perTenant, "perTenant");
    }
}
