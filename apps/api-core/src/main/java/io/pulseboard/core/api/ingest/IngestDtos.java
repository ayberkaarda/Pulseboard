package io.pulseboard.core.api.ingest;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Bodies of the ingestion endpoint. The request records only describe
 * {@code contracts/events/metric-batch.schema.json} in the OpenAPI document; the endpoint validates the raw body
 * against that schema instead of binding them.
 */
public final class IngestDtos {

    private IngestDtos() {
    }

    @Schema(name = "MetricBatch",
            description = "A batch of metric points (contracts/events/metric-batch.schema.json).",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record MetricBatchRequest(
            @ArraySchema(minItems = 1, maxItems = 1000, schema = @Schema(implementation = MetricPointRequest.class),
                    arraySchema = @Schema(requiredMode = REQUIRED))
            List<MetricPointRequest> points,
            @Schema(description = "Optional identifier of the sender.", minLength = 1, maxLength = 128,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String source) {
    }

    @Schema(name = "MetricPoint", description = "One sample of a metric.",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record MetricPointRequest(
            @Schema(requiredMode = REQUIRED) UUID metricId,
            @Schema(requiredMode = REQUIRED,
                    description = "RFC 3339 date-time with an offset; stored with microsecond precision.")
            OffsetDateTime ts,
            @Schema(requiredMode = REQUIRED,
                    description = "A finite number with |value| <= 1e100. A larger magnitude or a number beyond "
                            + "the double range rejects only this point (listed in `rejected`), not the batch.")
            double value) {
    }

    @Schema(name = "IngestResponse", description = "Outcome of an ingestion batch.")
    public record IngestResponse(
            @Schema(requiredMode = REQUIRED,
                    description = "Number of points stored (rows written); accepted + rejected = points sent.")
            int accepted,
            @Schema(requiredMode = REQUIRED, description = "Points that were not stored, in ascending index order.")
            List<RejectedPoint> rejected) {
    }

    @Schema(name = "RejectedPoint")
    public record RejectedPoint(
            @Schema(requiredMode = REQUIRED, description = "Zero-based index of the point in the batch.") int index,
            @Schema(requiredMode = REQUIRED,
                    description = "Why the point was not stored: unknown metric | timestamp is outside the range "
                    + "accepted for storage | timestamp cannot be represented | value must be a finite number | "
                    + "value magnitude must not exceed 1e100 | superseded by a later point with the same metricId "
                    + "and ts.", example = "unknown metric")
            String reason) {
    }
}
