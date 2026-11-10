package io.pulseboard.core.api.anomaly;

import io.pulseboard.core.domain.anomaly.Detector;
import io.pulseboard.core.domain.anomaly.Severity;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Response bodies of the anomaly endpoints. */
public final class AnomalyDtos {

    private AnomalyDtos() {
    }

    @Schema(name = "Anomaly", description = "A scored window of a metric that a detector flagged. The same "
            + "representation is the data of every server-sent event on /api/v1/stream/anomalies.")
    public record AnomalyResponse(
            UUID id,
            UUID tenantId,
            UUID metricId,
            @Schema(description = "Service that owns the metric.") UUID serviceId,
            @Schema(description = "Inclusive start of the scored window.") Instant windowStart,
            @Schema(description = "End of the scored window.") Instant windowEnd,
            @Schema(description = "Anomaly score between 0 and 1.", minimum = "0", maximum = "1") double score,
            Detector detector,
            Severity severity,
            @Schema(description = "Detector specific details.",
                    additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
            Map<String, Object> details,
            @Schema(description = "When the anomaly was stored.") Instant createdAt,
            @Schema(description = "User that acknowledged the anomaly; null while it is open, or when that user "
                    + "was deleted afterwards.", nullable = true) UUID acknowledgedBy,
            @Schema(description = "When the anomaly was acknowledged; null while it is open.", nullable = true)
            Instant acknowledgedAt) {
    }

    @Schema(name = "AnomalyPage", description = "One page of anomalies, newest window first.")
    public record AnomalyPageResponse(
            List<AnomalyResponse> items,
            @Schema(description = "Zero-based page number.") int page,
            @Schema(description = "Requested page size.") int size,
            @Schema(description = "Number of anomalies that match the filters.") long totalItems,
            @Schema(description = "Number of pages of this size.") long totalPages) {
    }

    /**
     * Describes {@code contracts/events/anomaly-detected.schema.json} in the OpenAPI document; the endpoint
     * validates the raw body against that schema instead of binding this record.
     */
    @Schema(name = "AnomalyDetectedNotification",
            description = "Anomalies the analytics service just stored "
                    + "(contracts/events/anomaly-detected.schema.json).",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record NotificationRequest(
            @ArraySchema(minItems = 1, maxItems = 500, schema = @Schema(implementation = NotifiedItem.class),
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<NotifiedItem> anomalies) {
    }

    @Schema(name = "AnomalyDetectedItem", description = "One stored anomaly. Only id and tenantId are used by the "
            + "server; the other members are informational.",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record NotifiedItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID tenantId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID metricId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant windowStart,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant windowEnd,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "1") double score,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Detector detector,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Severity severity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {
    }

    @Schema(name = "AnomalyNotificationResult")
    public record NotificationResponse(
            @Schema(description = "Distinct anomalies found in the database and published.") int accepted,
            @Schema(description = "Distinct entries whose id does not exist or belongs to another tenant than "
                    + "the one claimed.") int unknown) {
    }
}
