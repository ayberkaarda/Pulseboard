package io.pulseboard.core.application.common;

/**
 * A backing store the operation cannot safely run without is unreachable. The operation fails closed and is
 * rendered as 503 with a {@code Retry-After} header.
 */
public class DependencyUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
