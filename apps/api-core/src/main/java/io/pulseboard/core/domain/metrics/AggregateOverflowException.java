package io.pulseboard.core.domain.metrics;

/**
 * An aggregate of stored samples exceeds the double range. Ingestion bounds sample values so that this cannot
 * happen for data it wrote; the exception guards against samples written by other means.
 */
public class AggregateOverflowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AggregateOverflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
