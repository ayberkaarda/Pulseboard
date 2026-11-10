package io.pulseboard.core.domain.alert;

/** Where a raised alert is delivered. */
public enum AlertChannel {
    /** HTTP POST of a signed JSON document to the rule's URL. */
    WEBHOOK,
    /** One structured log line; real e-mail delivery is out of scope. */
    EMAIL_LOG
}
