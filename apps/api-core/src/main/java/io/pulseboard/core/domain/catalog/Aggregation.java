package io.pulseboard.core.domain.catalog;

/** How samples of a metric are combined when downsampled. */
public enum Aggregation {
    AVG,
    SUM,
    MAX,
    P95
}
