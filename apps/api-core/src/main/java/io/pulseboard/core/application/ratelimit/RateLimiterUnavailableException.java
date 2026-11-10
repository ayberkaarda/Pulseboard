package io.pulseboard.core.application.ratelimit;

/**
 * The rate-limit store is unreachable. Limited operations fail closed (503) rather than run unthrottled.
 */
public class RateLimiterUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RateLimiterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
