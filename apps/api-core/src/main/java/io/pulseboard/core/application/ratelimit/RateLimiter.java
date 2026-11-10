package io.pulseboard.core.application.ratelimit;

import java.time.Duration;

/**
 * Port for distributed, atomic token-bucket rate limiting. Buckets are identified by a namespace (what is limited,
 * for example {@code auth-ip}) and a subject (who is limited, for example a client address). Callers should pass
 * a hash rather than personal data as the subject.
 */
public interface RateLimiter {

    /**
     * Tries to take one token from the bucket.
     *
     * @throws RateLimiterUnavailableException when the backing store cannot be reached (callers fail closed)
     */
    Decision tryAcquire(String namespace, String subject, RateLimitPolicy policy);

    /**
     * Takes one token or throws {@link RateLimitExceededException} carrying the time until a token is available.
     */
    default void acquireOrThrow(String namespace, String subject, RateLimitPolicy policy) {
        Decision decision = tryAcquire(namespace, subject, policy);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfter());
        }
    }

    /** Outcome of one acquisition attempt; {@code retryAfter} is zero when allowed. */
    record Decision(boolean allowed, Duration retryAfter) {
    }
}
