package io.pulseboard.core.application.anomaly;

import io.pulseboard.core.domain.anomaly.Anomaly;

/** Fans a stored anomaly out to the live streams of its tenant. */
public interface AnomalyPublisher {

    /**
     * @throws io.pulseboard.core.application.common.DependencyUnavailableException when the message bus is not
     *     reachable
     */
    void publish(Anomaly anomaly);
}
