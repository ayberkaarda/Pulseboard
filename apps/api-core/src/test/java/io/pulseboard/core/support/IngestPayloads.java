package io.pulseboard.core.support;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Builds raw ingestion bodies, so tests control the exact bytes that are sent. */
public final class IngestPayloads {

    private IngestPayloads() {
    }

    /** One point; {@code value} is written verbatim, so it may be any JSON number literal. */
    public record Point(UUID metricId, String ts, String value) {

        public static Point of(UUID metricId, Instant ts, double value) {
            return new Point(metricId, ts.toString(), Double.toString(value));
        }
    }

    public static String batch(List<Point> points) {
        StringBuilder json = new StringBuilder(points.size() * 110 + 16).append("{\"points\":[");
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"metricId\":\"").append(point.metricId())
                    .append("\",\"ts\":\"").append(point.ts())
                    .append("\",\"value\":").append(point.value())
                    .append('}');
        }
        return json.append("]}").toString();
    }

    public static String single(UUID metricId, Instant ts, double value) {
        return batch(List.of(Point.of(metricId, ts, value)));
    }
}
