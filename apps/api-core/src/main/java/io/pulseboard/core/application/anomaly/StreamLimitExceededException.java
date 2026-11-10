package io.pulseboard.core.application.anomaly;

/** A tenant, or the instance as a whole, holds as many live streams as allowed; rendered as 429. */
public class StreamLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StreamLimitExceededException(String message) {
        super(message);
    }
}
