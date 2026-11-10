package io.pulseboard.core.application.ratelimit;

import java.time.Duration;
import java.util.Objects;

/**
 * Token-bucket policy: a bucket holds at most {@code capacity} tokens and refills at {@code capacity} tokens per
 * {@code refillPeriod}, continuously. Each permitted request consumes one token.
 */
public record RateLimitPolicy(int capacity, Duration refillPeriod) {

    public RateLimitPolicy {
        Objects.requireNonNull(refillPeriod, "refillPeriod");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        if (refillPeriod.toMillis() < 1) {
            throw new IllegalArgumentException("refillPeriod must be at least one millisecond");
        }
    }
}
