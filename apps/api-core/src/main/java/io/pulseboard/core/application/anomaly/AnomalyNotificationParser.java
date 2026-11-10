package io.pulseboard.core.application.anomaly;

import java.util.List;

/** Parses a raw anomaly notification and validates it against the anomaly-detected JSON Schema. */
public interface AnomalyNotificationParser {

    /**
     * @throws io.pulseboard.core.application.common.InvalidRequestException when the body is not JSON or violates
     *     the schema
     */
    List<NotifiedAnomaly> parse(byte[] json);
}
