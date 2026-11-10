package io.pulseboard.core.application.alert.webhook;

/**
 * A webhook target is not acceptable. The message is a fixed phrase that is safe to show to the caller; it never
 * contains the URL.
 */
public class UnsafeWebhookTargetException extends Exception {

    private static final long serialVersionUID = 1L;

    private final boolean transientFailure;

    public UnsafeWebhookTargetException(String reason, boolean transientFailure) {
        super(reason);
        this.transientFailure = transientFailure;
    }

    /** {@code true} when a later attempt may succeed (a DNS failure), {@code false} when the target is rejected. */
    public boolean isTransientFailure() {
        return transientFailure;
    }
}
