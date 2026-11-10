package io.pulseboard.core.api.metrics;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

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
            @Schema(requiredMode = REQUIRED) UUID metricId,
            @Schema(requiredMode = REQUIRED,
                    description = "How the samples of each bucket are combined (from the metric definition).")
            Aggregation aggregation,
            @Schema(requiredMode = REQUIRED,
                    description = "Inclusive start of the queried range, rounded up to whole microseconds.")
            Instant from,
            @Schema(requiredMode = REQUIRED,
                    description = "Exclusive end of the queried range, rounded up to whole microseconds.")
            Instant to,
            @Schema(requiredMode = REQUIRED,
                    description = "Bucket width used (ISO 8601 duration).", example = "PT5M") String step,
            @Schema(requiredMode = REQUIRED, description = "Non-empty buckets in ascending order; at most 2000.")
            List<MetricPointBucket> points) {
    }

    @Schema(name = "MetricPointBucket")
    public record MetricPointBucket(
            @Schema(requiredMode = REQUIRED,
                    description = "Start of the bucket (epoch-aligned; the first bucket may start before from).")
            Instant ts,
            @Schema(requiredMode = REQUIRED,
                    description = "Aggregated value of the bucket; null when the aggregate is not a finite number "
                    + "(not reachable with values accepted by ingestion).", types = {"number", "null"},
                    format = "double")
            Double value,
            @Schema(requiredMode = REQUIRED, description = "Number of samples in the bucket.") long count) {
    }
}
