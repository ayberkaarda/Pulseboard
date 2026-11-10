package io.pulseboard.core.infrastructure.ratelimit;

import io.pulseboard.core.application.auth.AuthThrottlingPolicy;
import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authentication throttling settings bound from {@code pulseboard.rate-limit.auth.*}.
 *
 * @param clientCapacity register/login/refresh requests per client address per {@code clientRefillPeriod}
 * @param clientRefillPeriod time to refill the per-client bucket
 * @param accountCapacity login attempts per tenant slug and email per {@code accountRefillPeriod}
 * @param accountRefillPeriod time to refill the per-account bucket
 */
@ConfigurationProperties("pulseboard.rate-limit.auth")
public record AuthRateLimitProperties(
        int clientCapacity,
        Duration clientRefillPeriod,
        int accountCapacity,
        Duration accountRefillPeriod) {

    public AuthThrottlingPolicy toPolicy() {
        return new AuthThrottlingPolicy(new RateLimitPolicy(clientCapacity, clientRefillPeriod),
                new RateLimitPolicy(accountCapacity, accountRefillPeriod));
    }
}
