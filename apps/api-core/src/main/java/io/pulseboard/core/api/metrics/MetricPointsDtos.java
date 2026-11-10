package io.pulseboard.core.api.metrics;

import io.pulseboard.core.domain.catalog.Aggregation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response bodies of the metric query endpoint. */
public final class MetricPointsDtos {

    private MetricPointsDtos() {
    }

    @Schema(name = "MetricPoints", description = "A metric downsampled into epoch-aligned buckets.")
    public record MetricPointsResponse(
            UUID metricId,
            @Schema(description = "How the samples of each bucket are combined (from the metric definition).")
            Aggregation aggregation,
            @Schema(description = "Inclusive start of the queried range, rounded up to whole microseconds.")
            Instant from,
            @Schema(description = "Exclusive end of the queried range, rounded up to whole microseconds.")
            Instant to,
            @Schema(description = "Bucket width used (ISO 8601 duration).", example = "PT5M") String step,
            @Schema(description = "Non-empty buckets in ascending order; at most 2000.")
            List<MetricPointBucket> points) {
    }

    @Schema(name = "MetricPointBucket")
    public record MetricPointBucket(
            @Schema(description = "Start of the bucket (epoch-aligned; the first bucket may start before from).")
            Instant ts,
            @Schema(description = "Aggregated value of the bucket; null when the aggregate is not a finite number "
                    + "(not reachable with values accepted by ingestion).", types = {"number", "null"},
                    format = "double")
            Double value,
            @Schema(description = "Number of samples in the bucket.") long count) {
    }
}
