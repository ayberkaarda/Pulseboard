package io.pulseboard.core.domain.metrics;

/** Result of writing one {@link MetricSample}. */
public enum SampleWriteOutcome {

    /** The sample was inserted, or replaced the value stored for the same metric and timestamp. */
    STORED,

    /**
     * A later sample of the same write has the same metric and timestamp; only that later sample is written
     * (last write wins), this one is not.
     */
    SUPERSEDED,

    /**
     * The metric does not exist for the writing tenant. A metric of another tenant yields this outcome as well,
     * so its existence is never disclosed.
     */
    UNKNOWN_METRIC,

    /** No storage is provisioned for the timestamp (no time partition covers it); nothing was written. */
    OUTSIDE_STORAGE_RANGE
}
