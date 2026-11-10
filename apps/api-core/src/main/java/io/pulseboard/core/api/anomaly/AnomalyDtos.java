package io.pulseboard.core.api.anomaly;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

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
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) UUID tenantId,
            @Schema(requiredMode = REQUIRED) UUID metricId,
            @Schema(requiredMode = REQUIRED, description = "Service that owns the metric.") UUID serviceId,
            @Schema(requiredMode = REQUIRED, description = "Name of the metric.") String metricName,
            @Schema(requiredMode = REQUIRED, description = "Name of the service that owns the metric.")
            String serviceName,
            @Schema(requiredMode = REQUIRED, description = "Inclusive start of the scored window.") Instant windowStart,
            @Schema(requiredMode = REQUIRED, description = "End of the scored window.") Instant windowEnd,
            @Schema(requiredMode = REQUIRED,
                    description = "Anomaly score between 0 and 1.", minimum = "0", maximum = "1") double score,
            @Schema(requiredMode = REQUIRED) Detector detector,
            @Schema(requiredMode = REQUIRED) Severity severity,
            @Schema(requiredMode = REQUIRED, description = "Detector specific details.",
                    additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
            Map<String, Object> details,
            @Schema(requiredMode = REQUIRED, description = "When the anomaly was stored.") Instant createdAt,
            @Schema(description = "User that acknowledged the anomaly; null while it is open, or when that user "
                    + "was deleted afterwards.", requiredMode = REQUIRED, nullable = true) UUID acknowledgedBy,
            @Schema(requiredMode = REQUIRED, description = "When the anomaly was acknowledged; null while it is open.",
                    nullable = true)
            Instant acknowledgedAt) {
    }

    @Schema(name = "AnomalyPage", description = "One page of anomalies, newest window first.")
    public record AnomalyPageResponse(
            @Schema(requiredMode = REQUIRED) List<AnomalyResponse> items,
            @Schema(requiredMode = REQUIRED, description = "Zero-based page number.") int page,
            @Schema(requiredMode = REQUIRED, description = "Requested page size.") int size,
            @Schema(requiredMode = REQUIRED,
                    description = "Number of anomalies that match the filters.") long totalItems,
            @Schema(requiredMode = REQUIRED, description = "Number of pages of this size.") long totalPages) {
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
                    arraySchema = @Schema(requiredMode = REQUIRED))
            List<NotifiedItem> anomalies) {
    }

    @Schema(name = "AnomalyDetectedItem", description = "One stored anomaly. Only id and tenantId are used by the "
            + "server; the other members are informational.",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record NotifiedItem(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) UUID tenantId,
            @Schema(requiredMode = REQUIRED) UUID metricId,
            @Schema(requiredMode = REQUIRED) Instant windowStart,
            @Schema(requiredMode = REQUIRED) Instant windowEnd,
            @Schema(requiredMode = REQUIRED, minimum = "0", maximum = "1") double score,
            @Schema(requiredMode = REQUIRED) Detector detector,
            @Schema(requiredMode = REQUIRED) Severity severity,
            @Schema(requiredMode = REQUIRED) Instant createdAt) {
    }

    @Schema(name = "AnomalyNotificationResult")
    public record NotificationResponse(
            @Schema(requiredMode = REQUIRED,
                    description = "Distinct anomalies found in the database and published.") int accepted,
            @Schema(requiredMode = REQUIRED,
                    description = "Distinct entries whose id does not exist or belongs to another tenant than "
                    + "the one claimed.") int unknown) {
    }
}
