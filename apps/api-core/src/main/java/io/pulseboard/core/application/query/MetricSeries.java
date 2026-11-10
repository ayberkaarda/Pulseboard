package io.pulseboard.core.application.query;

import io.pulseboard.core.domain.catalog.Aggregation;
import io.pulseboard.core.domain.metrics.SampleBucket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A downsampled series of one metric.
 *
 * @param metricId the metric
 * @param aggregation how the samples of each bucket were combined (from the metric definition)
 * @param from inclusive start of the queried range
 * @param to exclusive end of the queried range
 * @param step bucket width actually used
 * @param buckets non-empty buckets in ascending order
 */
public record MetricSeries(UUID metricId, Aggregation aggregation, Instant from, Instant to, Duration step,
        List<SampleBucket> buckets) {

    public MetricSeries {
        buckets = List.copyOf(buckets);
    }
}
