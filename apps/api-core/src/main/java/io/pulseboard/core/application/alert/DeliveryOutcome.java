package io.pulseboard.core.application.alert;

import java.time.Instant;

/**
 * Result of one delivery attempt.
 *
 * @param kind what to do with the event next
 * @param error sanitised reason for RETRY and FAILED: a status code or an error class, never a URL or response text
 * @param until for DEFER, when the attempt may be made
 */
public record DeliveryOutcome(Kind kind, String error, Instant until) {

    /** What happens to the event. */
    public enum Kind {
        SENT,
        /** Try again later, if attempts are left. */
        RETRY,
        /** Give up now. */
        FAILED,
        /** Not tried (the receiver is being left alone); put back without counting an attempt. */
        DEFER
    }

    public static DeliveryOutcome sent() {
        return new DeliveryOutcome(Kind.SENT, null, null);
    }

    public static DeliveryOutcome retry(String error) {
        return new DeliveryOutcome(Kind.RETRY, error, null);
    }

    public static DeliveryOutcome failed(String error) {
        return new DeliveryOutcome(Kind.FAILED, error, null);
    }

    public static DeliveryOutcome defer(Instant until) {
        return new DeliveryOutcome(Kind.DEFER, null, until);
    }
}
