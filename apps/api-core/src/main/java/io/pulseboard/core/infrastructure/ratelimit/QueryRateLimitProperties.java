package io.pulseboard.core.infrastructure.ratelimit;

import io.pulseboard.core.application.query.MetricQueryPolicy;
import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Metric query quota bound from {@code pulseboard.rate-limit.query.*}.
 *
 * @param tenantCapacity queries per tenant per {@code refillPeriod}
 * @param refillPeriod time to refill a tenant's bucket completely
 */
@ConfigurationProperties("pulseboard.rate-limit.query")
public record QueryRateLimitProperties(int tenantCapacity, Duration refillPeriod) {

    public MetricQueryPolicy toPolicy() {
        return new MetricQueryPolicy(new RateLimitPolicy(tenantCapacity, refillPeriod));
    }
}
