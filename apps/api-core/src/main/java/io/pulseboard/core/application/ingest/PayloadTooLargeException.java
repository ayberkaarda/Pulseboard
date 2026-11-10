package io.pulseboard.core.application.ingest;

/** The raw request body is larger than the configured maximum; rendered as 413. */
public class PayloadTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long maxBytes;

    public PayloadTooLargeException(long maxBytes) {
        super("The request body exceeds the maximum of " + maxBytes + " bytes");
        this.maxBytes = maxBytes;
    }

    public long getMaxBytes() {
        return maxBytes;
    }
}
