package io.pulseboard.core.infrastructure.ratelimit;

import io.pulseboard.core.application.apikey.ApiKeyClientPolicy;
import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-client limits of API key requests bound from {@code pulseboard.rate-limit.api-key.*}.
 *
 * @param clientCapacity requests carrying {@code X-Api-Key} per client address per {@code clientRefillPeriod}
 * @param clientRefillPeriod time to refill the per-client bucket
 * @param invalidCapacity failed API key authentications per client address per {@code invalidRefillPeriod}
 * @param invalidRefillPeriod time to refill the failed-authentication bucket
 */
@ConfigurationProperties("pulseboard.rate-limit.api-key")
public record ApiKeyClientRateLimitProperties(
        int clientCapacity,
        Duration clientRefillPeriod,
        int invalidCapacity,
        Duration invalidRefillPeriod) {

    public ApiKeyClientPolicy toPolicy() {
        return new ApiKeyClientPolicy(new RateLimitPolicy(clientCapacity, clientRefillPeriod),
                new RateLimitPolicy(invalidCapacity, invalidRefillPeriod));
    }
}
