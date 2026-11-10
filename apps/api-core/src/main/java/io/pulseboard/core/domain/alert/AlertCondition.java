package io.pulseboard.core.domain.alert;

/** What makes a rule fire. */
public enum AlertCondition {
    /** A stored metric point crosses a fixed value. */
    THRESHOLD,
    /** A detector reports an anomaly of at least a given severity. */
    ANOMALY_SEVERITY
}
