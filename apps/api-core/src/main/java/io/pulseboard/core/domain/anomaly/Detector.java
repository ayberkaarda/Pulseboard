package io.pulseboard.core.domain.anomaly;

/** Algorithm that produced an anomaly. */
public enum Detector {
    ZSCORE,
    ISOLATION_FOREST
}
