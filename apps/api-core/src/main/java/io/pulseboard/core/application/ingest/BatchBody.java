package io.pulseboard.core.application.ingest;

/**
 * Lazily read raw request body of an ingestion batch. It is read only after the caller passed the rate limit,
 * so throttled callers never cost a body read or a parse.
 */
@FunctionalInterface
public interface BatchBody {

    /**
     * Reads the complete body.
     *
     * @throws PayloadTooLargeException when the body exceeds the configured maximum size
     * @throws io.pulseboard.core.application.common.InvalidRequestException when the body cannot be read
     */
    byte[] read();
}
