package io.pulseboard.core.application.ratelimit;

import java.time.Duration;

/** The caller exhausted its token bucket; rendered as 429 with a {@code Retry-After} header. */
public class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super("Too many requests");
        this.retryAfter = retryAfter;
    }

    /** Whole seconds until a retry can succeed, never less than one. */
    public long retryAfterSeconds() {
        long millis = retryAfter == null ? 0 : retryAfter.toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }
}
