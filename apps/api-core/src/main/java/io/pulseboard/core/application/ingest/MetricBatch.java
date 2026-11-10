package io.pulseboard.core.application.ingest;

import java.util.List;

/**
 * A schema-valid ingestion batch ({@code contracts/events/metric-batch.schema.json}).
 *
 * @param points the points in request order
 * @param source optional free-form identifier of the sender, or {@code null}
 */
public record MetricBatch(List<Point> points, String source) {

    public MetricBatch {
        points = List.copyOf(points);
    }

    /**
     * One point as sent. The schema guarantees the formats of {@code metricId} (UUID) and {@code timestamp}
     * (RFC 3339 date-time); {@code value} may still be non-finite when the sent number exceeds the double range.
     *
     * @param index zero-based position in the {@code points} array
     */
    public record Point(int index, String metricId, String timestamp, double value) {
    }
}
